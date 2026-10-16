package com.ivy.data.backup.local

/**
 * Backups are named `ivy-backup-<epochMillis>.db`.
 *
 * The timestamp lives in the NAME and not only in the file system metadata, because ordering and
 * rotation must stay deterministic: a copy of the backup folder that loses `lastModified` (some
 * file managers and some "backup the backup" tools do) still lists and rotates in the right order.
 */
object BackupFileName {
    const val PREFIX: String = "ivy-backup-"
    const val EXTENSION: String = ".db"

    fun of(timestampEpochMillis: Long): String = "$PREFIX$timestampEpochMillis$EXTENSION"

    /**
     * The timestamp encoded in [fileName], or `null` when the name is not one of ours. A name that
     * carries a number too large for a `Long` is deliberately treated as "not ours" instead of
     * crashing.
     */
    fun timestampOfOrNull(fileName: String): Long? {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(EXTENSION)) return null
        val timestamp = fileName.removePrefix(PREFIX).removeSuffix(EXTENSION)
        // ASCII digits only: `toLongOrNull` follows Character.digit and would also accept non-ASCII
        // digits such as the full-width "１７００...", which no name we write ever contains.
        if (timestamp.isEmpty() || timestamp.any { it !in '0'..'9' }) return null
        return timestamp.toLongOrNull()
    }

    fun isBackupName(fileName: String): Boolean = timestampOfOrNull(fileName) != null
}
