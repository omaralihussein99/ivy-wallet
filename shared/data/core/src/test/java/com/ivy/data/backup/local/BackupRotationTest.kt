package com.ivy.data.backup.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Rotation is the part that can destroy data, so every rule is pinned here on the JVM: keeping the
 * newest N, pruning the oldest, determinism, and every awkward directory shape (missing, empty, full
 * of foreign files).
 */
class BackupRotationTest {

    private val tempDirectories = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("ivy-backup-test").toFile().also { tempDirectories += it }

    private fun backupFile(directory: File, timestamp: Long, size: Int = 4096): File =
        File(directory, BackupFileName.of(timestamp)).apply { writeBytes(ByteArray(size)) }

    @After
    fun tearDown() {
        tempDirectories.forEach { it.deleteRecursively() }
    }

    private fun entryOf(timestamp: Long, size: Long = 4096L) =
        BackupEntry(
            fileName = BackupFileName.of(timestamp),
            sizeBytes = size,
            lastModifiedEpochMillis = 0L,
        )

    private fun agedBackups(from: Long, to: Long): List<BackupEntry> =
        (from..to).map { entryOf(it * 1000L) }

    // ------------------------------------------------------------ the directory scan

    @Test
    fun `a directory that does not exist is simply empty`() {
        val missing = File(tempDir(), "not-there-yet")

        assertFalse(missing.exists())
        assertTrue(BackupDirectory.scan(missing).isEmpty())
        assertTrue(BackupDirectory.restorableNewestFirst(missing).isEmpty())
        assertTrue(BackupRotation.filesToDelete(BackupDirectory.scan(missing)).isEmpty())
    }

    @Test
    fun `an empty directory is simply empty`() {
        val empty = tempDir()

        assertTrue(BackupDirectory.scan(empty).isEmpty())
        assertTrue(BackupRotation.filesToDelete(BackupDirectory.scan(empty)).isEmpty())
    }

    @Test
    fun `the scan reports our backups with their size and lists them newest first`() {
        val directory = tempDir()
        backupFile(directory, 1000L, size = 2048)
        backupFile(directory, 3000L, size = 512)
        backupFile(directory, 2000L, size = 4096)

        assertEquals(
            listOf(
                BackupFileName.of(3000L) to 512L,
                BackupFileName.of(2000L) to 4096L,
                BackupFileName.of(1000L) to 2048L,
            ),
            BackupDirectory.restorableNewestFirst(directory).map { it.fileName to it.sizeBytes },
        )
    }

    @Test
    fun `foreign files and sub directories are visible to the scan but are not our backups`() {
        val directory = tempDir()
        backupFile(directory, 1000L)
        File(directory, "notes.txt").writeText("mine")
        File(directory, "sub").mkdirs()

        assertEquals(2, BackupDirectory.scan(directory).size) // the backup and notes.txt, no directory
        assertEquals(
            listOf(BackupFileName.of(1000L)),
            BackupDirectory.backups(directory).map { it.fileName },
        )
    }

    // ------------------------------------------------------------ keeping the newest N

    @Test
    fun `rotation keeps the newest N usable backups and prunes the older ones`() {
        val files = agedBackups(from = 1, to = 10)

        val toDelete = BackupRotation.filesToDelete(files, keepCount = 7)

        // Oldest first, so a pass reads in the order the files disappear.
        assertEquals(
            listOf(1000L, 2000L, 3000L).map(BackupFileName::of),
            toDelete,
        )
    }

    @Test
    fun `rotation does nothing when there are fewer backups than the retention count`() {
        val files = agedBackups(from = 1, to = 3)

        assertTrue(BackupRotation.filesToDelete(files, keepCount = 7).isEmpty())
    }

    @Test
    fun `rotation deletes nothing from an empty directory`() {
        assertTrue(BackupRotation.filesToDelete(emptyList()).isEmpty())
    }

    @Test
    fun `rotation never touches files that are not backups of ours`() {
        val files = agedBackups(from = 1, to = 5) +
            BackupEntry("notes.txt", 10L, 0L) +
            BackupEntry("ivywallet.db", 4096L, 0L) +
            BackupEntry("backup.json", 4096L, 0L)

        val toDelete = BackupRotation.filesToDelete(files, keepCount = 2)

        assertEquals(listOf(1000L, 2000L, 3000L).map(BackupFileName::of), toDelete)
    }

    @Test
    fun `a backup a restore is using survives rotation even when it is older than the newest N`() {
        val files = agedBackups(from = 1, to = 10)
        val inUse = BackupFileName.of(1000L)

        val toDelete = BackupRotation.filesToDelete(
            files = files,
            keepCount = 3,
            protectedFileNames = setOf(inUse),
        )

        assertFalse("the in-use backup must be kept", toDelete.contains(inUse))
        assertEquals(
            listOf(2000L, 3000L, 4000L, 5000L, 6000L, 7000L).map(BackupFileName::of),
            toDelete,
        )
    }

    @Test
    fun `an unusable backup of ours is pruned even when it is one of the newest`() {
        val files = listOf(
            entryOf(5000L, size = 0L), // ours, but nothing can be restored from it
            entryOf(4000L),
            entryOf(3000L),
        )

        val toDelete = BackupRotation.filesToDelete(files, keepCount = 2)

        assertEquals(listOf(BackupFileName.of(5000L)), toDelete)
    }

    @Test
    fun `the delete list is ordered oldest first so a pass is reproducible`() {
        val files = agedBackups(from = 1, to = 5) // 1000 to 5000

        val toDelete = BackupRotation.filesToDelete(files, keepCount = 2)

        assertEquals(listOf(1000L, 2000L, 3000L).map(BackupFileName::of), toDelete)
    }

    @Test
    fun `a keep count below one is rejected instead of deleting every backup`() {
        try {
            BackupRotation.filesToDelete(agedBackups(from = 1, to = 3), keepCount = 0)
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("keepCount"))
        }
    }

    @Test
    fun `applying the plan leaves exactly the newest N files on disk`() {
        val directory = tempDir()
        (1L..10L).forEach { backupFile(directory, it * 1000L) }
        File(directory, "notes.txt").writeText("not ours")

        val toDelete = BackupRotation.filesToDelete(
            files = BackupDirectory.scan(directory),
            keepCount = 3,
        )
        toDelete.forEach { File(directory, it).delete() }

        assertEquals(
            listOf(10000L, 9000L, 8000L).map(BackupFileName::of).sorted(),
            BackupDirectory.backups(directory).map { it.fileName }.sorted(),
        )
        assertTrue("the foreign file survives", File(directory, "notes.txt").exists())
    }
}
