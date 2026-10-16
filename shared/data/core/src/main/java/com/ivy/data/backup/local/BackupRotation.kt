package com.ivy.data.backup.local

/**
 * Rotation: keep the newest N usable backups, delete the rest.
 *
 * Pure (it reasons about names and sizes, it does not touch the file system), so it is unit tested on
 * the JVM. The rules, in the order they matter:
 *
 *  - Only files whose name is one of ours are ever candidates for deletion. A `notes.txt`, a
 *    `backup.json` from a previous export, a sub directory or anything else the user dropped into the
 *    folder is invisible to rotation and is never deleted.
 *  - Ordering is by the timestamp encoded in the file NAME, newest first, with the file name as the
 *    tie breaker, so the outcome is deterministic even when two backups share a millisecond.
 *  - A usable backup is kept when it is among the newest `keepCount`. Anything else of ours is
 *    deleted, which includes an unusable one (a zero byte or truncated `ivy-backup-*.db`): a file we
 *    cannot restore from is only occupying retention slots.
 *  - A file in [protectedFileNames] is never deleted, whatever its age or state. This is how a backup
 *    that a restore is currently reading survives a rotation that runs in the meantime.
 */
object BackupRotation {

    /**
     * The names of the files that should be deleted, oldest first.
     *
     * @param files every entry of the backup directory, ours and foreign ones alike.
     * @param keepCount how many usable backups to keep. Must be positive.
     * @param protectedFileNames files that must survive this pass (e.g. the one a restore is using).
     */
    fun filesToDelete(
        files: List<BackupEntry>,
        keepCount: Int = LocalBackupConfig.RETENTION_COUNT,
        protectedFileNames: Set<String> = emptySet(),
    ): List<String> {
        require(keepCount > 0) { "keepCount must be positive but was $keepCount" }

        val ours = files.filter { BackupFileName.isBackupName(it.fileName) }
        val kept = BackupUsability.restorableNewestFirst(ours)
            .take(keepCount)
            .map { it.fileName }
            .toSet()

        return ours
            .filterNot { it.fileName in kept }
            .filterNot { it.fileName in protectedFileNames }
            .sortedWith(BackupUsability.OLDEST_FIRST)
            .map { it.fileName }
    }
}
