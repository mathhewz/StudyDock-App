package com.studydock.app.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.studydock.app.ui.exportDatabaseToUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val AUTO_BACKUP_PREFS = "studydock_auto_backup"
private const val KEY_TREE_URI = "tree_uri"
private const val KEY_ENABLED = "enabled"
private const val KEY_LAST_AT = "last_backup_at"
private const val KEY_LAST_STATUS = "last_backup_status"
private const val AUTO_BACKUP_JOB_ID = 7401
private const val AUTO_BACKUP_PREFIX = "studydock-auto-"
private const val MAX_RETAINED_BACKUPS = 5

data class AutoBackupState(
    val treeUri: String? = null,
    val enabled: Boolean = false,
    val lastBackupAt: Long = 0L,
    val lastBackupStatus: String = ""
)

object AutoBackupScheduler {
    private val fileStampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", Locale.ROOT)

    fun readState(context: Context): AutoBackupState {
        val prefs = context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
        return AutoBackupState(
            treeUri = prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() },
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            lastBackupAt = prefs.getLong(KEY_LAST_AT, 0L),
            lastBackupStatus = prefs.getString(KEY_LAST_STATUS, "").orEmpty()
        )
    }

    fun setTreeUri(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putBoolean(KEY_ENABLED, true)
            .apply()
        scheduleDaily(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) scheduleDaily(context) else cancel(context)
    }

    fun scheduleDaily(context: Context) {
        val state = readState(context)
        if (!state.enabled || state.treeUri.isNullOrBlank()) return
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val info = JobInfo.Builder(AUTO_BACKUP_JOB_ID, ComponentName(context, AutoBackupJobService::class.java))
            .setPeriodic(24L * 60L * 60L * 1000L)
            .setPersisted(true)
            .setRequiresBatteryNotLow(true)
            .build()
        scheduler.schedule(info)
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        scheduler.cancel(AUTO_BACKUP_JOB_ID)
    }

    fun runBackupNow(context: Context): String {
        val state = readState(context)
        val rawTree = state.treeUri ?: return saveStatus(context, "Nenhuma pasta SAF configurada.")
        var createdUri: Uri? = null
        return try {
            val treeUri = Uri.parse(rawTree)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val stamp = fileStampFormatter.format(Instant.now().atZone(ZoneId.systemDefault()))
            val fileName = "$AUTO_BACKUP_PREFIX$stamp.db"
            val targetUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "application/octet-stream",
                fileName
            ) ?: error("Não foi possível criar arquivo na pasta selecionada.")
            createdUri = targetUri

            val resultMsg = exportDatabaseToUri(context, targetUri)
            if (resultMsg.startsWith("Falha") || !resultMsg.contains("sucesso", ignoreCase = true)) {
                error(resultMsg.removePrefix("Falha ao salvar o backup: ").trim())
            }
            createdUri = null
            rotateOldBackups(context, treeUri, treeDocId)
            saveStatus(context, resultMsg)
        } catch (err: kotlinx.coroutines.CancellationException) {
            createdUri?.let { uri ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
            throw err
        } catch (err: Throwable) {
            createdUri?.let { uri ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
            StudyLog.e("AutoBackup", "Falha no backup automático SAF", err)
            saveStatus(context, "Falha no backup automático: ${err.message ?: "erro desconhecido"}")
        }
    }

    private fun rotateOldBackups(context: Context, treeUri: Uri, treeDocId: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val entries = mutableListOf<Triple<String, Long, String>>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                if (name.startsWith(AUTO_BACKUP_PREFIX) && name.endsWith(".db")) {
                    val modified = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L
                    entries.add(Triple(docId, modified, name))
                }
            }
        }
        if (entries.size <= MAX_RETAINED_BACKUPS) return
        entries.sortedWith(compareByDescending<Triple<String, Long, String>> { it.second }.thenByDescending { it.third })
            .drop(MAX_RETAINED_BACKUPS)
            .forEach { (oldDocId, _, _) ->
                runCatching {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, oldDocId)
                    DocumentsContract.deleteDocument(context.contentResolver, docUri)
                }
            }
    }

    private fun saveStatus(context: Context, status: String): String {
        context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .putString(KEY_LAST_STATUS, status)
            .apply()
        return status
    }
}

class AutoBackupJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeJob: Job? = null
    @Volatile private var stoppedBySystem = false

    override fun onStartJob(params: JobParameters?): Boolean {
        stoppedBySystem = false
        activeJob?.cancel()
        lateinit var launchedJob: Job
        launchedJob = serviceScope.launch {
            try {
                AutoBackupScheduler.runBackupNow(applicationContext)
            } finally {
                if (!stoppedBySystem && activeJob === launchedJob) {
                    jobFinished(params, false)
                }
            }
        }
        activeJob = launchedJob
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        stoppedBySystem = true
        activeJob?.cancel()
        return true
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
