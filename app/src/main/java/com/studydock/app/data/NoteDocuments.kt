package com.studydock.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlin.math.abs

@Entity(tableName = "note_links", primaryKeys = ["sourceId", "reference"], foreignKeys = [
    ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["targetId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("targetId")])
data class NoteLink(val sourceId: Long, val reference: String, val targetId: Long)

@Entity(tableName = "note_drafts", foreignKeys = [
    ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("noteId")])
data class NoteDraft(
    @PrimaryKey val key: String,
    val noteId: Long?, val baseRevision: Long?, val title: String, val markdown: String,
    val bindings: String = "{}", val selectionStart: Int = 0, val selectionEnd: Int = 0,
    val revision: Long = 0, val pending: Boolean = true, val updatedAt: Long = System.currentTimeMillis()
)

data class PublishedNote(val note: Note, val draft: NoteDraft)
class NoteConflict(message: String = "Esta nota mudou em outro lugar. Seu rascunho foi preservado.") : IllegalStateException(message)
class DraftConflict : IllegalStateException("Este rascunho mudou em outra janela. Salve uma cópia para preservar sua edição.")

private const val SNAPSHOT_MIN_CHAR_DELTA = 24
private const val SNAPSHOT_MIN_INTERVAL_MS = 5L * 60L * 1000L

/**
 * Returns `true` when saving a note warrants recording a version snapshot:
 * if the title changed, if at least 24 characters were inserted/removed/replaced,
 * if no previous snapshot timestamp exists, or if at least 5 minutes have elapsed since [lastSnapshotAt].
 */
fun shouldCreateSnapshot(
    prevTitle: String,
    prevMarkdown: String,
    nextTitle: String,
    nextMarkdown: String,
    lastSnapshotAt: Long?,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (prevTitle == nextTitle && prevMarkdown == nextMarkdown) return false
    if (prevTitle != nextTitle) return true
    if (abs(nextMarkdown.length - prevMarkdown.length) >= SNAPSHOT_MIN_CHAR_DELTA) return true

    val minLen = minOf(prevMarkdown.length, nextMarkdown.length)
    var prefix = 0
    while (prefix < minLen && prevMarkdown[prefix] == nextMarkdown[prefix]) prefix++
    var suffix = 0
    val maxSuffix = minLen - prefix
    while (suffix < maxSuffix &&
        prevMarkdown[prevMarkdown.length - 1 - suffix] == nextMarkdown[nextMarkdown.length - 1 - suffix]
    ) {
        suffix++
    }
    val changedSpan = maxOf(prevMarkdown.length, nextMarkdown.length) - prefix - suffix
    if (changedSpan >= SNAPSHOT_MIN_CHAR_DELTA) return true
    if (lastSnapshotAt == null) return true
    return (now - lastSnapshotAt) >= SNAPSHOT_MIN_INTERVAL_MS
}

internal suspend fun publishNoteDraft(dao: StudyDao, key: String): PublishedNote {
    val draft = requireNotNull(dao.noteDraft(key)) { "O rascunho não está disponível." }
    require(draft.title.isNotBlank()) { "Informe um título. O texto está guardado no rascunho." }
    val stored = draft.noteId?.let { dao.findNote(it) ?: throw NoteConflict() }
    if (stored != null && (stored.trashedAt != null || stored.revision != draft.baseRevision)) throw NoteConflict()
    val headers = dao.allNoteLinkHeaders()
    val priorLinks = stored?.let { dao.linksFromNote(it.id).associate { row -> row.reference to row.targetId } }.orEmpty()
    val choices = priorLinks + NoteReferences.bindings(draft.bindings)
    val previous = stored ?: Note(title = draft.title.trim(), markdown = draft.markdown)
    val id = stored?.id ?: dao.insertNoteRow(previous)
    val next = previous.copy(id = id, title = draft.title.trim(), markdown = draft.markdown,
        revision = previous.revision + 1, updatedAt = System.currentTimeMillis())
    val nextHeader = NoteLinkHeader(
        id = id,
        title = next.title,
        trashedAt = next.trashedAt,
        headMarkdown = if (next.markdown.length > 2048) next.markdown.substring(0, 2048) else next.markdown
    )
    val resolutionHeaders = headers.filterNot { it.id == id } + nextHeader
    val bound = NoteReferences.boundReferencesFromHeaders(draft.markdown, resolutionHeaders, choices)
    dao.updateNoteRow(next)
    dao.replaceNoteLinks(id, bound)
    if (stored != null && stored.title != next.title) rewriteIncomingLinks(dao, id, next.title)
    val result = requireNotNull(dao.findNote(id))
    val links = dao.linksFromNote(id).associate { it.reference to it.targetId }
    val savedDraft = draft.copy(noteId = id, baseRevision = result.revision, title = draft.title,
        markdown = result.markdown, bindings = NoteReferences.encode(links), revision = draft.revision + 1, pending = false)
    dao.updateNoteDraftRow(savedDraft)
    return PublishedNote(result, savedDraft)
}

internal suspend fun rewriteIncomingLinks(dao: StudyDao, targetId: Long, title: String) {
    val incoming = dao.linksToNote(targetId).groupBy { it.sourceId }
    for ((sourceId, _) in incoming) {
        val source = dao.findNote(sourceId) ?: continue
        val original = dao.linksFromNote(sourceId).associate { it.reference to it.targetId }
        val replacements = mutableMapOf<String, String>()
        val occupied = original.filterValues { it != targetId }.toMutableMap()
        val references = NoteReferences.parse(source.markdown)
        for (ref in references) {
            if (original[ref.raw] != targetId) continue
            val fragment = when {
                ref.blockId != null -> "#^${ref.blockId}"
                ref.heading != null -> "#${ref.heading}"
                else -> null
            }
            val raw = replacements.getOrPut(ref.raw) {
                NoteReferences.reference(title, targetId, occupied, ref.label, fragment)
            }
            occupied[raw] = targetId
        }
        var markdown = source.markdown
        for (ref in references.asReversed()) {
            val replacement = replacements[ref.raw] ?: continue
            val prefix = if (ref.isEmbed) "![[" else "[["
            markdown = markdown.replaceRange(ref.start, ref.end, "$prefix$replacement]]")
        }
        if (markdown == source.markdown) continue
        val bindings = original.filterKeys { it !in replacements }.toMutableMap()
        replacements.values.forEach { bindings[it] = targetId }
        dao.updateNoteRow(source.copy(markdown = markdown, revision = source.revision + 1))
        dao.replaceNoteLinks(sourceId, bindings)
    }
}

internal suspend fun setNoteTrash(dao: StudyDao, id: Long, trashed: Boolean, expectedRevision: Long? = null) {
    val note = dao.findNote(id) ?: throw NoteConflict("Esta nota já foi removida.")
    if (expectedRevision != null && note.revision != expectedRevision) throw NoteConflict()
    if ((note.trashedAt != null) == trashed) return
    val revision = note.revision + 1
    dao.updateNoteRow(note.copy(trashedAt = if (trashed) System.currentTimeMillis() else null, revision = revision))
    dao.rebaseNoteDrafts(id, note.revision, revision)
}

internal suspend fun linkUnlinkedMentionInNote(
    dao: StudyDao,
    sourceId: Long,
    start: Int,
    end: Int,
    targetId: Long,
    targetTitle: String
): Boolean {
    val source = dao.findNote(sourceId) ?: return false
    if (source.trashedAt != null || start < 0 || end > source.markdown.length || start >= end) return false
    val term = source.markdown.substring(start, end)
    val rawTarget = if (term == targetTitle) {
        NoteReferences.escape(targetTitle)
    } else {
        "${NoteReferences.escape(targetTitle)}|${NoteReferences.escape(term)}"
    }
    val updatedMarkdown = source.markdown.replaceRange(start, end, "[[$rawTarget]]")
    val headers = dao.allNoteLinkHeaders()
    val priorLinks = dao.linksFromNote(sourceId).associate { it.reference to it.targetId }
    val bound = NoteReferences.boundReferencesFromHeaders(updatedMarkdown, headers, priorLinks + (rawTarget to targetId))
    val revision = source.revision + 1
    dao.updateNoteRow(source.copy(markdown = updatedMarkdown, revision = revision, updatedAt = System.currentTimeMillis()))
    dao.replaceNoteLinks(sourceId, bound)
    dao.rebaseNoteDrafts(sourceId, source.revision, revision)
    return true
}

internal val NOTES_MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notes ADD COLUMN trashedAt INTEGER")
        db.execSQL("CREATE TABLE note_links (sourceId INTEGER NOT NULL, reference TEXT NOT NULL, targetId INTEGER NOT NULL, PRIMARY KEY(sourceId,reference), FOREIGN KEY(sourceId) REFERENCES notes(id) ON DELETE CASCADE, FOREIGN KEY(targetId) REFERENCES notes(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_note_links_targetId ON note_links(targetId)")
        db.execSQL("CREATE TABLE note_drafts (`key` TEXT NOT NULL PRIMARY KEY, noteId INTEGER, baseRevision INTEGER, title TEXT NOT NULL, markdown TEXT NOT NULL, bindings TEXT NOT NULL, selectionStart INTEGER NOT NULL, selectionEnd INTEGER NOT NULL, revision INTEGER NOT NULL, pending INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_note_drafts_noteId ON note_drafts(noteId)")
        val notes = mutableListOf<Note>()
        db.query("SELECT id,title,markdown,updatedAt,pinned FROM notes").use { cursor ->
            while (cursor.moveToNext()) notes.add(Note(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4) != 0))
        }
        val titles = notes.groupBy { NoteReferences.key(it.title) }
        for (note in notes) for (ref in NoteReferences.parse(note.markdown).distinctBy { it.raw }) {
            val target = titles[NoteReferences.key(ref.target)]?.singleOrNull()
                ?: ref.noteTarget.takeIf { it.isNotBlank() }?.let { titles[NoteReferences.key(it)]?.singleOrNull() }
                ?: continue
            db.execSQL("INSERT INTO note_links(sourceId,reference,targetId) VALUES(?,?,?)", arrayOf(note.id, ref.raw, target.id))
        }
    }
}
