package com.ivy.data.backup.local

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Takes one local backup, then lets the repository rotate the older ones.
 *
 * Follows the pattern Ivy already uses for background work (`TransactionReminderWorker`): a
 * `@HiltWorker` `CoroutineWorker` whose dependencies are injected by the app's `HiltWorkerFactory`
 * (wired in `IvyAndroidApp`).
 */
@HiltWorker
class LocalBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: LocalBackupRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = repository.createBackup()
        return if (entry == null) {
            Timber.e("The local backup did not complete")
            Result.retry()
        } else {
            Timber.d("Local backup written as %s", entry.fileName)
            Result.success()
        }
    }

    companion object {
        /** Unique name of THE periodic backup. A single instance of this schedule may exist. */
        const val UNIQUE_PERIODIC_WORK_NAME = "ivy_local_backup_work"
    }
}
