package com.ivy.data.backup.local

/**
 * One file found in the backup directory, described without touching the file system again.
 *
 * A backup's identity and order come from its NAME ([timestampEpochMillis]); the file system only
 * contributes how big it is and when it was last touched, and the last one is display only.
 */
data class BackupEntry(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
) {
    /** The timestamp encoded in the file name, or `null` when this file is not one of our backups. */
    val timestampEpochMillis: Long?
        get() = BackupFileName.timestampOfOrNull(fileName)

    /** Can this file be offered for restore at all? See [BackupUsability]. */
    val isUsable: Boolean
        get() = BackupUsability.isUsable(this)
}
