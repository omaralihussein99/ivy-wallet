package com.ivy.data.backup.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The naming scheme is what makes ordering and rotation deterministic, so it is pinned on the JVM.
 */
class BackupFileNameTest {

    @Test
    fun `a backup name carries the timestamp in the name`() {
        assertEquals("ivy-backup-1700000000000.db", BackupFileName.of(1700000000000L))
    }

    @Test
    fun `the timestamp can be read back from the name`() {
        assertEquals(1700000000000L, BackupFileName.timestampOfOrNull("ivy-backup-1700000000000.db"))
        assertEquals(0L, BackupFileName.timestampOfOrNull("ivy-backup-0.db"))
        assertTrue(BackupFileName.isBackupName("ivy-backup-1700000000000.db"))
    }

    @Test
    fun `names that are not backups of ours are rejected`() {
        listOf(
            "notes.txt",
            "backup.db",
            "ivywallet.db",
            "ivy-backup-.db",
            "ivy-backup-abc.db",
            "ivy-backup-1700000000000.zip",
            "ivy-backup-1700000000000.db.bak",
            "my-ivy-backup-1700000000000.db",
            "ivy-backup-1700000000000.db-wal",
            "ivy-backup-１７０００００００００００.db",
        ).forEach { name ->
            assertNull(name, BackupFileName.timestampOfOrNull(name))
            assertFalse(name, BackupFileName.isBackupName(name))
        }
    }

    @Test
    fun `a number that does not fit into a Long is treated as not ours instead of crashing`() {
        assertNull(BackupFileName.timestampOfOrNull("ivy-backup-99999999999999999999.db"))
    }
}
