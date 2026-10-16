package com.ivy.data.backup.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.IvyRoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Local, on-device backups of the whole Ivy database.
 *
 * ## Why a copy of the database file and not the existing `IvyWalletCompleteData` export
 *
 * Ivy already has an export path (`com.ivy.data.backup.BackupDataUseCase.generateJsonBackup`), but it
 * was deliberately NOT reused here, because as a backup substrate it is incomplete and not
 * round-trip safe:
 *
 *  - `IvyWalletCompleteData` carries accounts, budgets, categories, loan records, loans, planned
 *    payments, settings, transactions, tags and tag associations. It does NOT carry `users`,
 *    `exchangeRates`, `shoppingLists` or `shoppingItems`, so a "backup" taken through it would
 *    silently lose whole tables that exist today (shopping lists were added by the shopping phase).
 *    Every new table would need this class to be edited, and forgetting means quiet data loss.
 *  - Its import path merges: `importJson` inserts with `saveMany` without clearing `transactions`,
 *    and rewrites foreign keys by matching account/category NAMES. Restoring "over" the current data
 *    would duplicate transactions and rewrite ids. That is an import, not a restore.
 *
 * Copying the SQLite file is complete by construction (all tables, present and future), needs no
 * serialisation model to keep in sync, and gives a restore that is a real point-in-time snapshot.
 *
 * ## Consistency: WAL, `-wal` and `-shm`
 *
 * Room opens the database in WAL mode, so a plain `cp ivywallet.db` can be torn or miss the newest
 * commits that still live in `ivywallet.db-wal`. This class does NOT hand-wave that:
 *
 *  1. Everything backup/restore related is serialised by [gate], so a backup and a restore can never
 *     run at the same time.
 *  2. `PRAGMA wal_checkpoint(TRUNCATE)` is executed first. That moves every COMMITTED frame out of the
 *     WAL into the main `.db` and truncates the `-wal` file, so the main file then holds every
 *     committed transaction. (If the database is not in WAL mode this is a no-op.)
 *  3. The `.db` file is copied from INSIDE `runInTransaction { }`, i.e. while Room's single writer
 *     connection holds its write transaction. While that transaction is open no other writer can
 *     COMMIT, and in WAL mode a writer appends to the log instead of rewriting main-database pages in
 *     place, so neither the main file nor the WAL can move under the copy: the copy is a torn-free
 *     snapshot of a well defined committed state. Writes that arrive while the copy runs simply
 *     commit after it (they are "after the backup", which is the correct meaning of a point-in-time
 *     backup).
 *  4. The copy is taken ONLY when the `-wal` file is empty, checked while the write transaction is
 *     held. A TRUNCATE checkpoint can be left incomplete (an active reader keeps SQLite from
 *     truncating the log) and a commit can slip in between the checkpoint and the lock; in either
 *     case the main file would be missing transactions that still live in the log. So the checkpoint
 *     is retried, and if the log cannot be emptied the attempt is abandoned and NO backup is written:
 *     a backup that silently misses the last transactions would be worse than no backup at all.
 *  5. The copy is verified: it must exist and start with the SQLite magic bytes, otherwise it is
 *     deleted and no backup is reported. A file we cannot restore is never offered as a backup.
 *  6. The `-wal`/`-shm` sidecars are deliberately NOT part of the backup: step 4 guarantees the main
 *     file is self-contained, so the backup stays valid when it is copied off the device.
 *
 * ## Restore
 *
 * Restore replaces the database file wholesale and then kills the process. The Room instance is a Hilt
 * `@Singleton` and closing it is irreversible, so a half-applied state is impossible by construction:
 * the swap is a single `rename(2)` (atomic on the same file system) and the process is terminated
 * immediately after it, before anything can touch the closed database. An alarm (which survives the
 * death of the process) relaunches the app, which opens the restored file from scratch. Nothing is
 * served from memory in between, and no stale state survives the restart because the process is gone.
 */
@Singleton
class LocalBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: IvyRoomDatabase,
    private val dispatchersProvider: DispatchersProvider,
) {

    /** Result of [restoreBackup]. */
    enum class RestoreResult {
        /** The database was replaced; the process is being restarted. */
        RESTARTING,

        /** No such backup file in the backup directory. */
        NOT_FOUND,

        /** The file exists but is not a usable SQLite database. */
        NOT_USABLE,

        /** The swap failed; nothing was changed. */
        FAILED,
    }

    private companion object {
        const val INCOMING_SUFFIX = ".incoming"
        const val RELAUNCH_REQUEST_CODE = 4201
        const val RELAUNCH_DELAY_MILLIS = 1_000L

        /** How often the WAL checkpoint is retried before the backup attempt is abandoned. */
        const val CHECKPOINT_ATTEMPTS = 3

        /** Pause between two checkpoint attempts, giving an active reader time to finish. */
        const val CHECKPOINT_RETRY_DELAY_MILLIS = 50L
    }

    /** Serialises backups, restores and the rotation against each other. */
    private val gate = Mutex()

    /** Backups a restore is currently using: rotation must never delete them. Guarded by [gate]. */
    private val inUse = mutableSetOf<String>()

    /**
     * The folder the backups live in: app-specific external storage, which needs no permission, is
     * visible to the user (file manager, USB copy) and is removed together with the app. Falls back to
     * internal storage when no external storage is mounted.
     */
    fun backupDirectory(): File {
        val external = context.getExternalFilesDir(null)
        val root = external ?: context.filesDir
        return File(root, LocalBackupConfig.BACKUP_DIRECTORY_NAME)
    }

    fun backupDirectoryPath(): String = backupDirectory().absolutePath

    /** Every file in the backup directory, ours and foreign ones alike. */
    suspend fun listFiles(): List<BackupEntry> = withContext(dispatchersProvider.io) {
        BackupDirectory.scan(backupDirectory())
    }

    /** The backups that can be offered for restore, newest first. */
    suspend fun listBackups(): List<BackupEntry> = withContext(dispatchersProvider.io) {
        BackupDirectory.restorableNewestFirst(backupDirectory())
    }

    /**
     * Writes one self-contained copy of the live database, then rotates.
     *
     * @return the new backup, or `null` when the copy could not be taken or verified (in which case no
     * partially written file is left behind).
     */
    suspend fun createBackup(timestampEpochMillis: Long = System.currentTimeMillis()): BackupEntry? =
        gate.withLock {
            withContext(dispatchersProvider.io) {
                val directory = backupDirectory()
                if (!directory.exists() && !directory.mkdirs()) {
                    Timber.e("Cannot create the backup directory %s", directory)
                    return@withContext null
                }

                val target = File(directory, BackupFileName.of(timestampEpochMillis))
                if (!copyLiveDatabaseInto(target)) {
                    target.delete()
                    return@withContext null
                }

                rotate(directory)
                target.toEntry()
            }
        }

    /** Deletes one backup file. Refuses while a restore is using it. */
    suspend fun deleteBackup(fileName: String): Boolean = gate.withLock {
        withContext(dispatchersProvider.io) {
            if (fileName in inUse) return@withContext false
            if (!BackupFileName.isBackupName(fileName)) return@withContext false
            val file = File(backupDirectory(), fileName)
            file.isFile && file.delete()
        }
    }

    /**
     * Replaces the live database with [fileName] and restarts the app.
     *
     * On [RestoreResult.RESTARTING] this function does not return normally in practice: the process is
     * killed right after the swap. See the class documentation for why the restart is part of the
     * restore rather than an optional extra.
     */
    suspend fun restoreBackup(fileName: String): RestoreResult = gate.withLock {
        withContext(dispatchersProvider.io) {
            if (!BackupFileName.isBackupName(fileName)) return@withContext RestoreResult.NOT_FOUND
            val source = File(backupDirectory(), fileName)
            if (!source.isFile) return@withContext RestoreResult.NOT_FOUND
            if (!hasSqliteHeader(source)) return@withContext RestoreResult.NOT_USABLE

            // From here on the file must survive a rotation, even if one runs before the process dies.
            inUse.add(fileName)
            val result = applyRestore(source)
            if (result != RestoreResult.RESTARTING) {
                inUse.remove(fileName)
            }
            result
        }
    }

    /** The names of the files that were deleted by a rotation pass, oldest first. */
    private fun rotate(directory: File): List<String> {
        val toDelete = BackupRotation.filesToDelete(
            files = BackupDirectory.scan(directory),
            keepCount = LocalBackupConfig.RETENTION_COUNT,
            protectedFileNames = inUse.toSet(),
        )
        toDelete.forEach {
            if (!File(directory, it).delete()) {
                Timber.w("Could not delete the rotated backup %s", it)
            }
        }
        return toDelete
    }

    /**
     * Steps 2-5 of the consistency story above: checkpoint, copy inside the write transaction once the
     * log is empty, and verify the header of the copy.
     */
    private fun copyLiveDatabaseInto(target: File): Boolean {
        val databaseFile = context.getDatabasePath(IvyRoomDatabase.DB_NAME)
        if (!databaseFile.isFile) {
            Timber.e("The database file %s does not exist", databaseFile)
            return false
        }
        return try {
            repeat(CHECKPOINT_ATTEMPTS) { attempt ->
                checkpointWal()

                var copied = false
                database.runInTransaction {
                    // While this transaction is open nothing can commit, so .db and -wal are frozen
                    // for the whole copy. The log must be empty, otherwise the main file is missing
                    // the frames that are still only in it.
                    if (!walIsEmpty(databaseFile)) return@runInTransaction
                    databaseFile.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied = true
                }

                if (copied) {
                    if (!hasSqliteHeader(target)) {
                        Timber.e("The backup copy %s is not a SQLite database", target)
                        return false
                    }
                    return true
                }

                if (attempt < CHECKPOINT_ATTEMPTS - 1) {
                    // A reader was still holding a snapshot while the checkpoint ran.
                    Thread.sleep(CHECKPOINT_RETRY_DELAY_MILLIS)
                }
            }

            Timber.e(
                "Could not empty the WAL of %s in %d attempts, so no backup was written",
                databaseFile,
                CHECKPOINT_ATTEMPTS,
            )
            false
        } catch (e: Exception) {
            Timber.e(e, "Could not copy the database into %s", target)
            false
        }
    }

    /** `true` when every committed frame has already been moved into the main database file. */
    private fun walIsEmpty(databaseFile: File): Boolean {
        val wal = File("${databaseFile.path}-wal")
        return !wal.exists() || wal.length() == 0L
    }

    /**
     * Moves every committed WAL frame into the main database file and truncates `-wal`. A no-op when
     * the database is not in WAL mode. Room's own `query` is used because `SupportSQLiteDatabase`
     * dropped `rawQuery` in androidx.sqlite 2.4.
     */
    private fun checkpointWal() {
        database.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).use { cursor ->
            // A PRAGMA only runs once the cursor is stepped.
            cursor.moveToFirst()
        }
    }

    private fun applyRestore(source: File): RestoreResult {
        val databaseFile = context.getDatabasePath(IvyRoomDatabase.DB_NAME)
        val incoming = File(databaseFile.parentFile, "${IvyRoomDatabase.DB_NAME}$INCOMING_SUFFIX")
        return try {
            // 1. Copy the backup next to the live database first. If anything fails here, the live
            //    database has not been touched at all.
            source.inputStream().use { input ->
                incoming.outputStream().use { output -> input.copyTo(output) }
            }
            if (!hasSqliteHeader(incoming)) {
                incoming.delete()
                return RestoreResult.NOT_USABLE
            }

            // 2. Flush the WAL into the main file, then close the database. Closing is what makes the
            //    file swap safe: no connection holds the file any more.
            checkpointWal()
            database.close()

            // 3. The sidecars belong to the database we are replacing, so they must not survive.
            sidecarFiles(databaseFile).forEach { it.delete() }

            // 4. one atomic rename(2) replaces the database file.
            if (!incoming.renameTo(databaseFile)) {
                Timber.e("Could not replace %s", databaseFile)
                incoming.delete()
                return RestoreResult.FAILED
            }

            // 5. The closed Room singleton cannot be reopened, so the process must go. The alarm
            //    outlives the process and brings the app back with the restored file.
            scheduleRelaunch()
            exitProcess(0)
        } catch (e: Exception) {
            Timber.e(e, "Restore from %s failed", source)
            incoming.delete()
            RestoreResult.FAILED
        }
    }

    private fun sidecarFiles(databaseFile: File): List<File> = listOf(
        File("${databaseFile.path}-wal"),
        File("${databaseFile.path}-shm"),
        File("${databaseFile.path}-journal"),
    )

    private fun scheduleRelaunch() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            RELAUNCH_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // A plain (inexact) alarm needs no permission and is delivered after this process is gone.
        alarmManager.set(
            AlarmManager.RTC,
            System.currentTimeMillis() + RELAUNCH_DELAY_MILLIS,
            pendingIntent,
        )
    }

    private fun hasSqliteHeader(file: File): Boolean = try {
        val header = ByteArray(BackupUsability.SQLITE_MAGIC.size)
        file.inputStream().use { it.read(header) }
        BackupUsability.hasSqliteHeader(header)
    } catch (e: Exception) {
        Timber.e(e, "Could not read the header of %s", file)
        false
    }

    private fun File.toEntry(): BackupEntry = BackupEntry(
        fileName = name,
        sizeBytes = length(),
        lastModifiedEpochMillis = lastModified(),
    )
}
