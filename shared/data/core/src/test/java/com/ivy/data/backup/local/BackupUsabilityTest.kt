package com.ivy.data.backup.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Which backups are usable" is a pure question, so it is answered here without a device.
 */
class BackupUsabilityTest {

    private fun entry(name: String, size: Long) =
        BackupEntry(fileName = name, sizeBytes = size, lastModifiedEpochMillis = 0L)

    private fun entryOf(timestamp: Long, size: Long = 4096L) = entry(BackupFileName.of(timestamp), size)

    private fun sqliteHeader(): ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(84)

    @Test
    fun `a backup is usable only when it is named like ours and is big enough to be a database`() {
        assertTrue(entryOf(1000L).isUsable)
        assertFalse("foreign file", entry("notes.txt", 4096L).isUsable)
        assertFalse("zero byte", entryOf(1000L, size = 0L).isUsable)
        assertFalse("truncated", entryOf(1000L, size = 99L).isUsable)
        assertTrue(
            "exactly one header",
            entryOf(1000L, size = LocalBackupConfig.MIN_USABLE_BACKUP_SIZE_BYTES).isUsable,
        )
    }

    @Test
    fun `only a real SQLite header is accepted`() {
        val header = sqliteHeader()
        assertEquals("a SQLite header is 100 bytes", 100, header.size)

        assertTrue(BackupUsability.hasSqliteHeader(header))
        assertTrue(BackupUsability.hasSqliteHeader(sqliteHeader() + ByteArray(200)))
        assertFalse("plain text", BackupUsability.hasSqliteHeader("Not a database file".toByteArray()))
        assertFalse("truncated magic", BackupUsability.hasSqliteHeader("SQLite".toByteArray()))
        assertFalse("empty", BackupUsability.hasSqliteHeader(ByteArray(0)))
        assertFalse("right length, wrong content", BackupUsability.hasSqliteHeader(ByteArray(100)))
    }

    @Test
    fun `the restorable list keeps the usable backups newest first and drops everything else`() {
        val entries = listOf(
            entryOf(1000L),
            entryOf(3000L),
            entryOf(2000L),
            entryOf(4000L, size = 0L), // ours, but impossible to restore
            entry("notes.txt", 4096L), // not ours at all
        )

        assertEquals(
            listOf(
                BackupFileName.of(3000L),
                BackupFileName.of(2000L),
                BackupFileName.of(1000L),
            ),
            BackupUsability.restorableNewestFirst(entries).map { it.fileName },
        )
    }

    @Test
    fun `an empty list of files has no restorable backups`() {
        assertTrue(BackupUsability.restorableNewestFirst(emptyList()).isEmpty())
    }
}
