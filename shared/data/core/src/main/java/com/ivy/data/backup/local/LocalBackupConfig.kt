package com.ivy.data.backup.local

/**
 * The tunables of the local automatic backup facility.
 *
 * They are named constants in one place on purpose: the worker, the rotation and the screen all
 * read them from here, so there is no magic number sprinkled around the feature.
 */
object LocalBackupConfig {
    /**
     * How many usable backups are kept. Rotation deletes everything older than the newest
     * [RETENTION_COUNT] usable backups.
     */
    const val RETENTION_COUNT: Int = 7

    /**
     * How often the automatic backup runs. WorkManager's own minimum for periodic work is
     * 15 minutes, so a daily schedule is well inside the supported range.
     */
    const val BACKUP_INTERVAL_HOURS: Long = 24L

    /**
     * A SQLite database file starts with a 100 byte header, so anything smaller can never be a
     * database and is treated as an unusable backup.
     */
    const val MIN_USABLE_BACKUP_SIZE_BYTES: Long = 100L

    /**
     * Folder, inside the app specific external storage, that holds the backup files. App specific
     * external storage needs no permission and is user inspectable (a file manager or a USB copy can
     * see and copy the backups), unlike `filesDir` and unlike `/data/data/<app>`.
     */
    const val BACKUP_DIRECTORY_NAME: String = "IvyWalletBackups"
}
