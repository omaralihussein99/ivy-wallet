# Phase 6: the scheduled backup never ran, and why

Phase 5 installed the first real APK and everything passed except the headline promise of the backup
feature: the **automatic (scheduled) backup never ran**. This note records the defect, its cause, the
one coherent fix, and exactly what was and was not observed on the device afterwards.

## The defect

Every cold start of the Phase 5 APK logged, four times and more:

```
E WM-WorkerFactory: Could not instantiate com.ivy.data.backup.local.LocalBackupWorker
E WM-WorkerFactory: java.lang.NoSuchMethodException: com.ivy.data.backup.local.LocalBackupWorker.<init>[Context, WorkerParameters]
E WM-WorkerWrapper: Could not create Worker com.ivy.data.backup.local.LocalBackupWorker
```

The manual "Back up now" kept working, because it calls `LocalBackupRepository` on the screen's own
coroutine and never goes through WorkManager.

## The cause

`WorkerFactory.createWorkerWithDefaultFallback` is what logs that line: it means the *factory* returned
`null` and WorkManager fell back to reflectively calling `(Context, WorkerParameters)` - a constructor an
`@AssistedInject` worker does not have.

Three of the four candidates were ruled out by reading the code and the *merged* manifest:

| candidate | verdict |
| --- | --- |
| worker annotation/constructor | correct: `@HiltWorker` + `@AssistedInject constructor(@Assisted appContext, @Assisted params, repository)` in [`LocalBackupWorker.kt`](shared/data/core/src/main/java/com/ivy/data/backup/local/LocalBackupWorker.kt:18) |
| `Application` not a `Configuration.Provider` | correct: [`IvyAndroidApp.kt`](app/src/main/java/com/ivy/wallet/IvyAndroidApp.kt:17) implements it and injects `HiltWorkerFactory` |
| default `androidx.startup` initializer still installed | absent: the merged demo manifest contains no `androidx.startup.InitializationProvider`, only WorkManager's own services, so the `tools:node="remove"` in [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml:56) held |
| `androidx.hilt:hilt-compiler` missing | **this is it** |

The real cause, in one sentence: the shared Hilt convention plugin put only Dagger's processor
(`com.google.dagger:hilt-android-compiler`) on the KSP processor path
([`buildSrc/src/main/kotlin/ivy.hilt.gradle.kts:15`](buildSrc/src/main/kotlin/ivy.hilt.gradle.kts) before the fix), while the
`androidx.hilt:hilt-compiler` artifact - the only thing that turns `@HiltWorker` into a
`<Worker>_AssistedFactory` plus the `@IntoMap @StringKey(...)` binding that `HiltWorkerFactory` looks the
worker up by - was not a dependency anywhere in the project ([`gradle/libs.versions.toml`](gradle/libs.versions.toml:100)
had `hilt-work` but no matching compiler). `HiltWorkerFactory` was therefore installed but its map had no
entry for the worker, `createWorker()` returned `null`, and the reflective fallback failed as logged.

Proof from the Phase 5 build output: KSP had generated only `LocalBackupWorker_Factory.java`,
`LocalBackupRepository_Factory.java` and `LocalBackupScheduler_Factory.java` for that package - no
`LocalBackupWorker_AssistedFactory` and no `LocalBackupWorker_HiltModule`.

The same gap applied to the app's other `@HiltWorker`, `TransactionReminderWorker` (in `temp/legacy-code`);
it just had not been due during the Phase 5 session, so only the backup worker surfaced the error.

## The fix

Two build files and one test - nothing in the backup feature itself:

1. [`gradle/libs.versions.toml`](gradle/libs.versions.toml:12): added the version `androidx-hilt = "1.2.0"`, added
   `androidx-hilt-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "androidx-hilt" }`, and pointed
   `hilt-work` at the same version so the runtime and the processor cannot drift apart.
2. [`buildSrc/src/main/kotlin/ivy.hilt.gradle.kts:23`](buildSrc/src/main/kotlin/ivy.hilt.gradle.kts): added
   `ksp(catalog.library("androidx-hilt-compiler"))` next to Dagger's processor, i.e. in *the* place every module
   gets its Hilt processing from, so any future `@HiltWorker` is wired by construction.
3. [`LocalBackupWorkerWiringTest.kt`](shared/data/core/src/test/java/com/ivy/data/backup/local/LocalBackupWorkerWiringTest.kt:1):
   two JVM tests that assert the generated `LocalBackupWorker_AssistedFactory` exists and implements
   `androidx.hilt.work.WorkerAssistedFactory`, and that `LocalBackupWorker_HiltModule` was generated. Those types are
   produced if and only if `androidx.hilt:hilt-compiler` ran over the module, so the defect cannot come back silently.

After the fix the processor emits exactly what was missing:

```java
@Generated("androidx.hilt.AndroidXHiltProcessor") @AssistedFactory
public interface LocalBackupWorker_AssistedFactory extends WorkerAssistedFactory<LocalBackupWorker> {}

@Module @InstallIn(SingletonComponent.class)
public interface LocalBackupWorker_HiltModule {
  @Binds @IntoMap @StringKey("com.ivy.data.backup.local.LocalBackupWorker")
  WorkerAssistedFactory<? extends ListenableWorker> bind(LocalBackupWorker_AssistedFactory factory);
}
```

Frozen decisions untouched: retention is still `RETENTION_COUNT = 7`, the interval still
`BACKUP_INTERVAL_HOURS = 24`, the unique work name still `ivy_local_backup_work`, the directory still
`files/IvyWalletBackups`, and the restore flow was not edited.

## Why migration 132 was not touched

No table, column, index or entity changed. The defect was entirely in how the Kotlin worker is constructed;
the SQLite schema is identical to the shipped Phase 5 APK. Migration 132 stays frozen and no 133 was created.

## Evidence

Module level (before the app build, which is the expensive one):

- `:shared:data:core:testDebugUnitTest :temp:legacy-code:compileDebugKotlin` -> `BUILD SUCCESSFUL in 5m 58s`.
- 18 JUnit XML files under `shared/data/core/build/test-results/testDebugUnitTest/`, 184 tests, 0 failures,
  0 errors, 0 skipped. The new one: `TEST-com.ivy.data.backup.local.LocalBackupWorkerWiringTest.xml` ->
  `tests=2 failures=0 errors=0`.
- `LocalBackupWorker_AssistedFactory.java`, `LocalBackupWorker_AssistedFactory_Impl.java`,
  `LocalBackupWorker_HiltModule.java` now appear in `shared/data/core/build/generated/ksp/debug/java/...`.

Build once:

- `call C:\ivy\gradlew.bat :app:assembleDemo --console=plain` -> `BUILD SUCCESSFUL in 27m 3s`,
  `950 actionable tasks: 941 executed, 9 up-to-date`.
- APK `app/build/outputs/apk/demo/app-demo.apk`, **13,563,402 bytes**, copied to
  `C:\ivy-artifacts\ivy-phase6-demo.apk`. Installed with `adb install -r` over the Phase 5 install, so the
  Phase 5 data survived.
- The installed variant is `demo`: `isMinifyEnabled = true`, `isDebuggable = false`. This is **not** an R8-off
  debug build. `app/build/outputs/mapping/demo/mapping.txt` shows
  `com.ivy.data.backup.local.LocalBackupWorker -> com.ivy.data.backup.local.LocalBackupWorker` (kept, not renamed),
  which is what keeps the `@StringKey` map lookup working under R8.

Device (`emulator-5554`, `sdk_gphone64_x86_64`, API 36, timezone `Asia/Jerusalem`):

1. **No instantiation error, and the worker actually runs.** After install and a clean cold start,
   `adb logcat -d` has no `Could not instantiate` / `WM-WorkerFactory` line at all, and it does have, seven times,
   ```
   I WM-WorkerWrapper: Worker result SUCCESS for Work [ id=037589d2-..., tags={ com.ivy.data.backup.local.LocalBackupWorker } ]
   ```
   WorkManager's own DB (`/data/data/com.ivy.wallet.debug/no_backup/androidx.work.workdb`, read via root sqlite3):
   `ivy_local_backup_work` -> worker `com.ivy.data.backup.local.LocalBackupWorker`, `interval_duration=86400000`,
   `state=0` (ENQUEUED), `period_count=7`, `run_attempt_count=0` - seven completed periods, zero retries.
   The very first one happened by itself on the first launch after install (06:14) and produced
   `ivy-backup-1792206860074.db` (155,648 bytes) with the app untouched.
2. **JobScheduler forcing alone is not enough on a periodic request** - worth recording. `cmd jobscheduler run -f
   com.ivy.wallet.debug 14` answered `Running job [FORCED]` and JobScheduler history shows
   `START ... STOP ... app called jobFinished` after 42 ms, but no file changed: WorkManager's own 24 h period
   guard short-circuits a periodic `WorkSpec` that is not due (the automatic run took 588 ms and did write a file).
   So the build's interval was left alone and the *device* was nudged instead: with the app force-stopped,
   `update WorkSpec set last_enqueue_time = 0` then `am start`, which makes WorkManager schedule the due periodic
   work immediately. Seven such runs added seven backups.
3. **Rotation to 7.** With 2 backups and a foreign file placed in the folder
   (`notes.txt`, 19 bytes, owner `root`), seven scheduled runs took the folder 3 -> 4 -> 5 -> 6 -> 7 -> 7 -> 7
   files: at the eighth backup the oldest (`ivy-backup-1792197833427.db`, 03:43) disappeared, at the ninth the next
   oldest (`...1792206860074.db`, 06:14). Final `adb shell ls -l` shows exactly seven `ivy-backup-*.db` files
   (06:18-06:29) **and `notes.txt` untouched**. The Backups screen lists the same seven.
   The manual path was confirmed to rotate identically: "Back up now" -> toast `Backup created.`, a new 06:29
   entry, still seven rows, oldest 06:18 pruned, `notes.txt` still there.
4. **Descendant exclusion in the parent picker.** Hierarchy screen shows `Bank` with `Parent: Cash`. Opening
   **Cash's** "Set parent" offers only `No parent` - Bank (its child) is not offered, and Cash itself is not
   offered. As a control, **Bank's** picker offers `No parent` and `Cash`, and not Bank. Screenshots
   `phase6/p6-cash-picker.png` and `phase6/p6-bank-picker.png`. A full round-trip (Bank -> No parent, then
   Bank -> Cash) updated the tree each time (`Rolled up: -82.00` disappeared and came back) and left Bank
   parented to Cash as before (`phase6/p6-hier-noparent.png`, `phase6/p6-hier-restored.png`).
5. **Cold start** (`am force-stop` then `am start -W`, demo build, R8 on): `TotalTime: 2983`, `LaunchState: COLD`
   and `TotalTime: 3239`, `LaunchState: COLD`. (Two earlier runs measured 7884 ms `WARM` - a background job had
   just restarted the process - and 4091 ms `COLD`.)
6. **Regression sanity with the new APK.** More hub (swipe down on the home header) still lists Backups and the
   entry still opens (`phase6/p6-more3.png`, `phase6/p6-backups.png`); "Back up now" still creates a listed backup
   (`phase6/p6-backup-now.png`); one shopping checkout still posted two ledger rows titled `Weekly groceries` with
   `Milk` 9.0 and `Bread` 3.0 in the description (verified in the device DB) and moved the buffer figure by 12.00
   (`phase6/p6-checkout.png`, `phase6/p6-posted.png`); one hierarchy parent round-trip still behaves (see 4).
7. **Logcat.** Fresh dump at `C:\ivy-artifacts\phase6\phase6-logcat-final.txt` (22,593 lines): no `FATAL EXCEPTION`,
   no `ANR in com.ivy`, no `Could not instantiate`.

## What is NOT verified

- **A real 24 h elapsed schedule.** No clock was advanced and no interval was changed in the build; the seven
  scheduled runs were made due by editing WorkManager's `last_enqueue_time` on the device (see 2). The periodic
  request itself still carries `interval_duration = 86400000`.
- **The reminder worker's actual notification.** It shares the repaired wiring, but Phase 6 never waited for a
  reminder to fire.
- **Release build.** Only `assembleDemo` was built and installed; no `assembleRelease` (with the release signing
  config) was produced or tested.
- **Cross-version restore.** The restore flow was deliberately not touched and not re-exercised here; Phase 5's
  restore evidence still stands on its own.
- **Other devices / API levels.** Everything above is `emulator-5554`, API 36.
- **OEM job scheduling.** Only stock AOSP JobScheduler behaviour on the emulator was observed.
- The shopping checkout consumed the only list, so the Shopping screen is empty afterwards (pre-existing
  behaviour, not a Phase 6 change).
- Side effect of the device nudge: `TransactionReminderWorker` was made due too and completed 6 extra periods, so
  its `period_count` and next-run time on this emulator no longer look like a fresh install.

## Artefacts

`C:\ivy-artifacts\ivy-phase6-demo.apk`, `C:\ivy-artifacts\phase6-assemble.log`,
`C:\ivy-artifacts\phase6-core-test.log`, `C:\ivy-artifacts\phase6-core-test2.log`, and in
`C:\ivy-artifacts\phase6\`: `phase6-logcat-01.txt`, `phase6-logcat-final.txt`, the `ui-*.xml` dumps and the
`p6-*.png` screenshots named above.
