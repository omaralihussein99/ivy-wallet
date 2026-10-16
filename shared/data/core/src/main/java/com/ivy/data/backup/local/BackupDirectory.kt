package com.ivy.data.backup.local

import java.io.File

/**
 * Reads a backup directory using `java.io.File` only - no Android and no Room - so every awkward
 * case (the directory does not exist yet, it is empty, it is unreadable, it contains files that are
 * none of our business) is exercised by JVM unit tests.
 *
 * None of these functions ever throws: a missing or unreadable directory simply looks empty, which is
 * what the caller (rotation) needs in order to do nothing instead of crashing.
 */
object BackupDirectory {

    /** Every regular file in [directory], or an empty list when it cannot be listed. */
    fun scan(directory: File): List<BackupEntry> {
        if (!directory.isDirectory) return emptyList()
        val files = directory.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile }
            .map {
                BackupEntry(
                    fileName = it.name,
                    sizeBytes = it.length(),
                    lastModifiedEpochMillis = it.lastModified(),
                )
            }
    }

    /** Only the files that follow the backup naming scheme, in no particular order. */
    fun backups(directory: File): List<BackupEntry> =
        scan(directory).filter { BackupFileName.isBackupName(it.fileName) }

    /** The backups that can be offered for restore, newest first. */
    fun restorableNewestFirst(directory: File): List<BackupEntry> =
        BackupUsability.restorableNewestFirst(scan(directory))
}
