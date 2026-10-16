# Phase 3 handover — local automatic backups

Tracking issues: **2709, 3467, 3672, 3177**.

This phase adds a local (on-device) automatic backup facility to the fork: a schedule that takes one
backup a day, a screen that lists the backups with their timestamp and size, a manual "Back up now",
and a strongly confirmed restore.

---

## What Phase 3 is

* A backup **is a copy of the whole Ivy database file**, taken from the live database, written into a
  folder the user can actually find with a file manager.
* One **periodic** schedule (`24 h`), plus a manual "Back up now".
* **Rotation**: the newest `7` backups are kept, older ones are deleted automatically.
* A Material3 **Backups screen**: every backup with its timestamp and size, the folder it lives in,
  restore and delete per row.
* **Restore**: replaces the database with the chosen backup and restarts the app.

---

## Decisions already made, do not relitigate

### 1. The backup is a copy of the SQLite file, not the existing export path

Ivy already had a full-data export (`com.ivy.data.backup.BackupDataUseCase.generateJsonBackup`), and it
was **deliberately not reused**. As a backup substrate it is incomplete and not round-trip safe:

* `IvyWalletCompleteData` carries accounts, budgets, categories, loan records, loans, planned payments,
  settings, transactions, tags and tag associations. It does **not** carry `users`, `exchangeRates`,
  `shoppingLists` or `shoppingItems` — so a backup taken through it would silently lose whole tables
  that exist today (shopping lists arrived in the shopping phase). Every new table would need this
  model to be edited, and forgetting means quiet data loss.
* Its import path **merges**: `importJson` inserts with `saveMany` without clearing `transactions`, and
  rewrites foreign keys by matching account/category **names**. Restoring "over" current data would
  duplicate transactions and remap ids. That is an import, not a restore.

Copying the database file is complete by construction (every table, present and future), needs no
serialisation model to keep in sync, and gives a restore that is a real point-in-time snapshot. The
reasoning is recorded in the KDoc of `LocalBackupRepository` so it does not have to be rediscovered.

### 2. Where the backups live

`Context.getExternalFilesDir(null)/IvyWalletBackups` — app-specific external storage. On a device that
is `Android/data/<applicationId>/files/IvyWalletBackups` (typically
`/storage/emulated/0/Android/data/com.ivy.wallet/files/IvyWalletBackups`). It needs **no permission**,
it is visible to a file manager / USB copy and it is removed together with the app. Falls back to
`filesDir` when no external storage is mounted. Never inside the repository.

### 3. Interval and retention are named constants

`LocalBackupConfig`: `RETENTION_COUNT = 7`, `BACKUP_INTERVAL_HOURS = 24`,
`MIN_USABLE_BACKUP_SIZE_BYTES = 100`, `BACKUP_DIRECTORY_NAME = "IvyWalletBackups"`. The worker, the
rotation and the screen all read from here — no magic numbers elsewhere.

### 4. A backup's identity and order come from its NAME

`ivy-backup-<epochMillis>.db`. Ordering must stay deterministic even if the folder is copied by
something that loses `lastModified`, so the timestamp is in the name. Only names we generate are
"ours": the prefix, the `.db` suffix and **ASCII digits** must all match (`BackupFileName`); a name
carrying a number too large for a `Long` is treated as "not ours" instead of crashing, and non-ASCII
digits (for example full-width `１７`) are rejected on purpose.

### 5. Restore is a file swap followed by a process restart

Not a merge, not a partial application. See below.

### 6. No schema change at all

Migration 132 is untouched (still the last migration) and no `Migration132to133` was introduced. The
preferred outcome of the brief — *no* schema change — is what happened; nothing in this phase touches
`IvyRoomDatabase`, its entities or its migrations.

### 7. Only the periodic backup goes through WorkManager

WorkManager is used exactly where it is needed: a backup that must happen while the app is closed.
The manual "Back up now" runs the same repository call on the screen's foreground coroutine, because
the user is standing in front of it and would otherwise have to wait for a moment that may never come
while the screen is open. Enqueueing it as unique work (`ExistingWorkPolicy.KEEP`) was removed for
that reason: an unused second code path would be worse than none.

---

## The WAL / consistency story (the correctness trap of this phase)

Room opens the database in WAL mode, so a naive `cp ivywallet.db` can be torn or can miss the newest
commits that still live in `ivywallet.db-wal`. `LocalBackupRepository` does not hand-wave that:

1. Everything backup/restore related is serialised by one `Mutex`, so a backup and a restore can never
   run at the same time (and a second backup cannot overlap a rotation).
2. `PRAGMA wal_checkpoint(TRUNCATE)` runs first: every COMMITTED frame moves from the WAL into the main
   `.db` and `-wal` is truncated. A no-op when the database is not in WAL mode.
3. The `.db` file is copied from **inside** `runInTransaction { }`, i.e. while Room's single writer
   connection holds its write transaction. While that transaction is open no other writer can COMMIT,
   and in WAL mode a writer appends to the log instead of rewriting main-database pages in place, so
   neither the main file nor the WAL can move under the copy. Writes that arrive while the copy runs
   commit *after* it, which is exactly what "a backup taken at time T" means.
4. The copy happens **only when `-wal` is empty**, checked while the write transaction is held. A
   TRUNCATE checkpoint can be left incomplete (an active reader keeps SQLite from truncating the log),
   and a commit can slip in between the checkpoint and the lock; in either case the main file would be
   missing transactions that still live in the log. So the checkpoint is retried
   (`CHECKPOINT_ATTEMPTS = 3`, `CHECKPOINT_RETRY_DELAY_MILLIS = 50`) and if the log still cannot be
   emptied the attempt is abandoned and **no backup is written**: a backup that silently misses the
   last transactions would be worse than no backup at all.
5. The copy is verified: it must start with the SQLite magic bytes (`SQLite format 3\0`), otherwise it
   is deleted and no backup is reported. A file we cannot restore is never offered as a backup.
6. `-wal`/`-shm` are deliberately **not** part of the backup. Step 4 guarantees the main file is
   self-contained, so a single copied file stays valid when the user copies it off the device.

So the guarantee is: *a listed backup is a complete, self-contained SQLite database containing every
transaction that was committed when it was taken.*

---

## Restore semantics

`LocalBackupRepository.restoreBackup(fileName)`:

1. The name must be ours and the file must exist, otherwise `NOT_FOUND` / the row is not offered.
2. It must start with the SQLite magic bytes, otherwise `NOT_USABLE` — the live database is untouched.
3. The backup is copied to `ivywallet.db.incoming` **next to the live database** first, so a failure at
   this point cannot damage anything.
4. A WAL checkpoint is run and the Room database is **closed**. Closing is what makes the swap safe:
   no connection holds the file any more.
5. The old sidecars (`-wal`, `-shm`, `-journal`) are deleted: they belong to the database being
   replaced and would otherwise be replayed into the restored file.
6. One atomic `rename(2)` (atomic on the same file system) replaces `ivywallet.db`.
7. An `AlarmManager` alarm (which outlives the process) relaunches the app, and the process is killed
   with `exitProcess(0)`.

**What this does about the live database and in-memory state:** the file is replaced wholesale, so
there is no half-applied state to fall back to — either the rename happened or it did not, and steps
3-4 mean a failure leaves the live database exactly as it was. The Room instance is a Hilt `@Singleton`
and closing it is irreversible, so the process is terminated immediately after the swap instead of
carrying on with a closed database; the restart re-opens the restored file from scratch. That also
removes every piece of stale in-memory state (DAO caches in other singletons, Compose state, the
"current" account/settings) by construction rather than by trying to refresh it.

**Confirmation:** restore is behind the same typed-confirmation gate the app already uses for
destructive actions (`DeleteConfirmationModal`'s typed delete button): the Restore button stays
disabled until the user has typed the backup's file name exactly.

---

## The tests that pin the rules

Rotation, naming and "which files are usable" are **pure** logic (no Android, no Room, no file system
in the rules themselves) in `shared/data/core/src/main/java/com/ivy/data/backup/local/`:
`BackupFileName`, `BackupUsability`, `BackupRotation`, `BackupDirectory`, `BackupDisplayFormat`.

| Suite (JUnit XML under `shared/data/core/build/test-results/testDebugUnitTest/`) | tests | failures | errors |
| --- | --- | --- | --- |
| `TEST-com.ivy.data.backup.local.BackupRotationTest.xml` | 13 | 0 | 0 |
| `TEST-com.ivy.data.backup.local.BackupDisplayFormatTest.xml` | 8 | 0 | 0 |
| `TEST-com.ivy.data.backup.local.BackupFileNameTest.xml` | 4 | 0 | 0 |
| `TEST-com.ivy.data.backup.local.BackupUsabilityTest.xml` | 4 | 0 | 0 |

`BackupRotationTest` covers: missing directory, empty directory, the scan's sizes and order, foreign
files and sub directories being never touched, keeping the newest N and pruning the older ones, fewer
backups than the retention count, an unusable backup of ours being pruned, the delete list being
ordered oldest first, a backup a restore is using surviving a rotation, `keepCount < 1` being rejected,
and applying the plan leaving exactly the newest N files plus the foreign file on disk.

Two of these tests failed on the first run and both were real findings, now fixed:
`BackupRotation`'s "oldest first" delete list was mis-expected in the test (production behaviour was
right), and `BackupFileName` accepted full-width digits because `toLongOrNull` follows
`Character.digit` — the rule is now explicitly ASCII `0`-`9`.

---

## Evidence

| What | Command | Result |
| --- | --- | --- |
| Touched modules compile + tests | `gradlew --console=plain :shared:data:core:testDebugUnitTest :shared:data:core:assembleDebug :shared:ui:core:assembleDebug :shared:ui:navigation:assembleDebug :feature:backup:assembleDebug :feature:home:assembleDebug` (log `C:\ivy-artifacts\phase3-build.log`) | `BUILD SUCCESSFUL in 6m 38s` (log line 3705) |
| Whole app still compiles (new `@HiltWorker`, new `@Singleton` injectables, new module) | `gradlew --console=plain :app:compileDemoKotlin` (log `C:\ivy-artifacts\phase3-appcompile.log`) | `BUILD SUCCESSFUL in 18m 55s` (log line 7139) |
| Whole `:shared:data:core` suite | JUnit XML, 18 suites | 182 tests, 0 failures, 0 errors |
| New dependency | `gradle/libs.versions.toml:120` → `androidx.work:work-runtime-ktx` (`androidx-work = "2.9.1"`), used at `shared/data/core/build.gradle.kts:19` | only new dependency of this phase |
| Database version | `shared/data/core/src/main/java/com/ivy/data/db/IvyRoomDatabase.kt:90` | `version = 132` — unchanged; `git diff --name-only` touches no migration file and no `IvyRoomDatabase.kt` |

Wiring, for the record: `settings.gradle.kts:23` (`include(":feature:backup")`),
`app/build.gradle.kts:135` (`implementation(projects.feature.backup)`),
`shared/ui/navigation/.../Screens.kt:171` (`data object BackupsScreen`),
`app/src/main/java/com/ivy/IvyNavGraph.kt:9,24,95`,
`feature/home/.../HomeMoreMenu.kt:583` (fourth row of the More hub, `R.drawable.home_more_menu_backups`
+ `R.string.backups_entry` in `shared/ui/core`),
`app/src/main/java/com/ivy/wallet/IvyAndroidApp.kt:22,39` (`backupScheduler.scheduleAutomaticBackups()`
on every start, unique work + `KEEP`, so the schedule cannot pile up).

The Backups screen shows bytes of file size, not money, so `FormatMoneyUseCase` is not involved;
`BackupDisplayFormat` renders sizes with integer arithmetic and no `String.format`, deliberately
locale-independent. If money is ever displayed on this screen it must go through `FormatMoneyUseCase`.

---

## What is NOT verified

* **No APK was built and nothing was installed.** `:app:assembleDemo` was deliberately not run, only
  `:app:compileDemoKotlin`. No emulator or device was touched in this phase.
* **No backup was ever taken at runtime.** That a scheduled job actually fires, that the file lands in
  the folder, and that the rotation deletes the right files on a real device are all unverified.
* **No restore was ever performed.** The rename, the alarm relaunch and `exitProcess(0)` are argued from
  the code and the platform contract, not observed.
* **The WAL behaviour was not observed against a real on-device database.** The checkpoint's result,
  the "is `-wal` empty" check and the failure path that refuses to write a stale backup are reasoned
  about, and the pure parts are unit tested, but the Android-side file IO has no test.
* **The screen itself has no UI test.** `:feature:backup` compiles, its view model is only exercised by
  reading it, and no Compose test drives it.
* **No migration ran against a real database**, because this phase needs no migration: the schema is
  untouched (version 132, no 133).
* Timestamps in the list are rendered with `java.time` in the system zone by
  `BackupDisplayFormat.timestamp`, not through Ivy's `TimeConverter`.

---

## Rules for working here

* All build logs, screenshots and pulled databases go to `C:\ivy-artifacts`, never into the repository.
* Verify with a build before claiming anything; quote the `BUILD SUCCESSFUL` line and read the real
  counts out of the JUnit XML under `<module>/build/test-results/**/*.xml`.
* `upstream` is fetch-only: push to `origin` (the fork) and never to upstream.
* Do not leave the work uncommitted with a dirty tree; the previous phase did and it blocked the next
  one.

## Files added by this phase

```
shared/data/core/src/main/java/com/ivy/data/backup/local/
    LocalBackupConfig.kt BackupFileName.kt BackupEntry.kt BackupUsability.kt
    BackupDirectory.kt BackupRotation.kt BackupDisplayFormat.kt
    LocalBackupRepository.kt LocalBackupWorker.kt LocalBackupScheduler.kt
shared/data/core/src/test/java/com/ivy/data/backup/local/
    BackupFileNameTest.kt BackupUsabilityTest.kt BackupRotationTest.kt BackupDisplayFormatTest.kt
shared/ui/core/src/main/res/drawable/home_more_menu_backups.xml
feature/backup/  (build.gradle.kts, strings.xml, BackupsScreen.kt, BackupsViewModel.kt, BackupsViewState.kt)
```
