package com.studydock.app.ui

import com.studydock.app.data.ReaderPreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ReaderAndImportSafetyTest {

    @Test
    fun readBoundedTextCapsCharactersStrictlyAndPreservesUtf8() {
        val sample = "Olá, Segundo Cérebro! ".repeat(500)
        val stream = ByteArrayInputStream(sample.toByteArray(Charsets.UTF_8))
        val bounded = ReaderPreferencesStore.readBoundedText(stream, maxChars = 120)
        assertEquals(120, bounded.length)
        assertEquals(sample.substring(0, 120), bounded)
    }

    @Test
    fun normalizeSafeVaultZipEntryPathRejectsTraversalAndLeadingSlash() {
        assertEquals("Notas/Zettelkasten.md", normalizeSafeVaultZipEntryPath("Notas\\Zettelkasten.md"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSafeVaultZipEntryPath("../secrets.md")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSafeVaultZipEntryPath("Notas/../../escape.md")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSafeVaultZipEntryPath("/absolute/note.md")
        }
    }

    @Test
    fun cleanupStaleDatabaseImportFilesRemovesOldBackupsAndKeepsFreshOnes() {
        val root = Files.createTempDirectory("studydock-cleanup-test").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val dbDir = File(root, "databases").apply { mkdirs() }
            val now = 1_795_000_000_000L

            val oldBefore = File(filesDir, "before-import.db").apply {
                writeText("old")
                setLastModified(now - 25L * 60L * 60L * 1000L)
            }
            val oldWal = File(filesDir, "before-import.db-wal").apply { writeText("wal") }
            val staleTemp = File(dbDir, "studydock-import-123.db").apply {
                writeText("temp")
                setLastModified(now - 2L * 60L * 60L * 1000L)
            }
            val freshTemp = File(dbDir, "studydock-import-999.db").apply {
                writeText("fresh")
                setLastModified(now - 60_000L)
            }

            cleanupStaleDatabaseImportFiles(filesDir, dbDir, nowMillis = now)

            assertFalse(oldBefore.exists())
            assertFalse(oldWal.exists())
            assertFalse(staleTemp.exists())
            assertTrue(freshTemp.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
