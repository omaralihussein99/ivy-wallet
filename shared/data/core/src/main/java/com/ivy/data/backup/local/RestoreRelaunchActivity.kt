package com.ivy.data.backup.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import timber.log.Timber

/**
 * Relay that re-opens Ivy after [LocalBackupRepository] replaced the database and killed the process.
 *
 * ## Why a relay activity in its own process
 *
 * Every *background-initiated* activity start is refused on Android 14+ when the app is not in front
 * of the user. All three obvious mechanisms were implemented and measured on the API 36 device, and
 * all three were blocked with `BAL_BLOCK` in `ActivityTaskManager`:
 *
 *  1. `AlarmManager.setAlarmClock` + `PendingIntent.getActivity` (the alarm fired ~2.5 s after the
 *     swap; `callingUidProcState: NONEXISTENT`).
 *  2. The same, with the creator side opt-in
 *     `ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`
 *     - a byte-identical verdict, the opt-in changes nothing.
 *  3. `PendingIntent.getBroadcast` into a receiver, which then called `startActivity` - the process
 *     *was* started for the broadcast (`Start proc ... for broadcast {...RestoreRelaunchReceiver}`)
 *     but the start was refused again (`callingUidProcState: RECEIVER`).
 *
 * A start issued by a **visible activity** is always allowed, so the relaunch is done the way
 * `ProcessPhoenix` does it: while the restored app is still on screen (the user has just tapped
 * "Restore"), the repository starts this relay activity in a **separate process** and kills the main
 * process. This activity has its own process, so it survives that kill, and because it is itself
 * resumed it is allowed to start the launcher activity of a brand new main process, which opens the
 * restored database.
 *
 * Everything here is deliberate:
 *  - the start happens in [onResume], not in `onCreate`, so this activity is already resumed (i.e.
 *    visible to the platform) when it starts the launcher activity;
 *  - the relay is `excludeFromRecents`, `noHistory` and translucent, so the user never sees it: it
 *    exists for a few milliseconds and immediately finishes.
 */
class RestoreRelaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No content: this activity is a relay, not a screen.
        if (savedInstanceState != null) finish()
    }

    override fun onResume() {
        super.onResume()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Timber.e("Restore relaunch: %s has no launch intent", packageName)
            finish()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(launchIntent)
            Timber.d("Restore relaunch: the launcher activity was started")
        } catch (e: Exception) {
            Timber.e(e, "Restore relaunch: the launcher activity could not be started")
        }
        finish()
    }
}
