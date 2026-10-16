package com.ivy.data.backup.local

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns THE automatic backup schedule: exactly one periodic request, forever.
 *
 * It is enqueued as unique work, so calling [scheduleAutomaticBackups] on every app start cannot pile
 * up duplicate schedules: `KEEP` leaves an existing schedule exactly as it is and only creates one
 * when none exists. That is the same shape `TransactionReminderLogic` uses, Ivy's existing user of
 * WorkManager.
 *
 * The manual "Back up now" is NOT routed through here: it runs on the foreground coroutine of the
 * backup screen, where the user is waiting for the answer, instead of being queued for a moment that
 * may never come while the screen is open.
 */
@Singleton
class LocalBackupScheduler @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /**
     * Ensures the single periodic backup exists. Idempotent on purpose: it is called on every app
     * start.
     */
    fun scheduleAutomaticBackups() {
        val request = PeriodicWorkRequestBuilder<LocalBackupWorker>(
            LocalBackupConfig.BACKUP_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            LocalBackupWorker.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
