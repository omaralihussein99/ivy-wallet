package com.ivy.data.backup.local

/**
 * "Which of the files in the backup directory can actually be restored?"
 *
 * Pure, so the answer is unit tested on the JVM instead of on a device. A file counts as usable only
 * when all of the following hold:
 *  - its name is one of ours (`ivy-backup-<epochMillis>.db`), i.e. we know when it was taken;
 *  - it is at least as large as a SQLite header, so a zero byte or truncated file is not offered;
 *  - (for the copy path) it starts with the SQLite magic bytes.
 */
object BackupUsability {
    /** The 16 magic bytes every SQLite database file starts with, including the trailing NUL. */
    val SQLITE_MAGIC: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Newest first, and deterministic: two backups with the same timestamp fall back to their names. */
    val NEWEST_FIRST: Comparator<BackupEntry> =
        compareByDescending<BackupEntry> { it.timestampEpochMillis }.thenByDescending { it.fileName }

    val OLDEST_FIRST: Comparator<BackupEntry> =
        compareBy<BackupEntry> { it.timestampEpochMillis }.thenBy { it.fileName }

    fun isUsable(entry: BackupEntry): Boolean =
        entry.timestampEpochMillis != null &&
            entry.sizeBytes >= LocalBackupConfig.MIN_USABLE_BACKUP_SIZE_BYTES

    /**
     * Does [header] start with the SQLite magic bytes? [header] is read as a fixed size prefix, so a
     * file shorter than the magic is rejected instead of matching a partially filled array.
     */
    fun hasSqliteHeader(header: ByteArray): Boolean =
        header.size >= SQLITE_MAGIC.size && SQLITE_MAGIC.indices.all { header[it] == SQLITE_MAGIC[it] }

    /** The usable backups, newest first. Unrelated files and unusable backups are dropped. */
    fun restorableNewestFirst(entries: List<BackupEntry>): List<BackupEntry> =
        entries.filter(::isUsable).sortedWith(NEWEST_FIRST)
}
