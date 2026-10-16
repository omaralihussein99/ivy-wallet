# Phase 4 handover — the date off-by-one bug class

Tracking issues: **3646, 3495, 3383, 3424, 3231**.

This phase diagnoses and fixes **one** defect that shows up as "a transaction/entry appears on the
previous (or next) day, or *today* resolves to the wrong day". It is a single expression, not five
bugs: every one of the five reports that is still reproducible in this tree comes from the same
missing conversion between Material3's date picker and Ivy's date plumbing.

---

## 1. The root cause, in one paragraph

`androidx.compose.material3.DatePickerState` is **UTC-day based**: `initialSelectedDateMillis` and
`selectedDateMillis` are documented as "UTC milliseconds from the epoch", i.e. a *calendar day* is
encoded as its **UTC midnight**. Ivy is not: it stores real `Instant`s and resolves their calendar
day with the app's zone through `TimeConverter`. The picker crossed that boundary with a
zone-aware conversion on both legs —
[`AndroidDateTimePicker.kt:55`](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:55)
(`initialSelectedDateMillis = viewState.initialDate?.toEpochMilli()`, the raw instant, so Material3
displayed the instant's **UTC** day) and
[`AndroidDateTimePicker.kt:63`](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:63)
(`Instant.ofEpochMilli(selectedDateMillis)` decoded with `TimeConverter.toLocalDate()`, i.e. the UTC
midnight of the tapped day reinterpreted in the app's zone). Out of that one mismatch: the picker
**opens on the previous day** for every zone east of UTC (the day the user confirms is then stored a
day early — Japan UTC+9, Israel UTC+3, India UTC+5:30), and the confirmed day is **decremented** for
every zone west of UTC (UTC-11). The widget is shared by all the affected screens, which is why four
different reports produce one shape of bug.

The fix makes the two legs use the encoding Material3 documents, and nothing else:
[`AndroidDateTimePicker.kt:191`](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:191)
(`initialSelectedDateMillis` = the app-zone day of the instant, or the local today, encoded as UTC
midnight) and
[`AndroidDateTimePicker.kt:202`](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:202)
(`pickedDate` = the UTC-midnight millis decoded in UTC). Both live in one small
`MaterialDatePickerDateBridge` in the same file, documented so the next reader does not have to
rediscover Material3's convention.

---

## 2. Issue mapping

| Issue | Reported symptom | Same expression? | Call site that produced it |
|---|---|---|---|
| **3646** | "Entering date for an expense always sets the date to one less than the date I picked" (reporter had travelled to Japan, UTC+9) | **Yes** — same bridge, *initial* leg | `EditTransactionViewModel.handleChangeDate` → `dateTimePicker.pickDate` ([`EditTransactionViewModel.kt:543`](feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionViewModel.kt:543)) → [`AndroidDateTimePicker.kt:61`](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:61) |
| **3495** | "The selected date is always one day earlier than intended" — adding income, adding expense, and the date shown on the dashboard | **Yes** — same bridge, both legs | same call site as 3646; the dashboard only renders the day the picker already stored wrong |
| **3231** | "I write today's transaction, but on the home page the date was recorded yesterday" | **Yes** — same bridge, *initial* leg with **no** date yet (`initialDate == null`) | a **new** transaction passes `initialDate = null` ([`EditTransactionViewModel.kt:544`](feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionViewModel.kt:544)); Material3 then defaulted to "today in UTC", which is yesterday for any zone east of UTC before the local day catches up |
| **3383** | "the date of my transaction changes to the next day" (unlisted account) | **No — genuinely different, and already fixed in this tree** | it was the *legacy* time picker's double conversion (`LocalTime.convertLocalToUtc()`), removed by upstream PR 3399; this tree already has the fixed line [`RootActivity.kt:228`](app/src/main/java/com/ivy/wallet/RootActivity.kt:228) (no `convertLocalToUtc()`), so there is nothing left to fix here |
| **3424** | "Incorrect Time Display After Saving Transaction" (time correct before saving, wrong after reopening; reporter UTC+5:30) | **No — genuinely different, and the defective expression is not in this tree** | upstream's fix (PR 3425) replaced a *default* date built from `timeNowUTC()`. This tree no longer has that: a new transaction's default is a real instant, [`EditTransactionViewModel.kt:725`](feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionViewModel.kt:725) `timeProvider.utcNow()`, and the time defaults from `timeProvider.localTimeNow()` |

So **3646 / 3495 / 3231 are the same expression reached through three different entry points**
(edit-transaction, add-transaction, dashboard-after-save — plus the loan screens, which call the same
`DateTimePicker.pickDate`: [`LoanViewModel.kt:276`](feature/loans/src/main/java/com/ivy/loans/loan/LoanViewModel.kt:276),
[`LoanDetailsViewModel.kt:481`](feature/loans/src/main/java/com/ivy/loans/loandetails/LoanDetailsViewModel.kt:481),
[`LoanDetailsViewModel.kt:522`](feature/loans/src/main/java/com/ivy/loans/loandetails/LoanDetailsViewModel.kt:522)),
while **3383 and 3424 are different causes** that are already resolved/absent here. They are not forced
into the narrative.

---

## 3. What was changed (and why it cannot move already-stored data)

One production file, one file added:

* [`shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt`](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt)
  — the two broken expressions were lifted into `MaterialDatePickerDateBridge`
  ([line 180](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:180)) and now
  do the UTC-midnight ↔ app-zone-day conversion; the composable calls them at
  [line 61](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:61) and
  [line 69](shared/ui/core/src/main/java/com/ivy/ui/time/impl/AndroidDateTimePicker.kt:69).
* [`shared/ui/core/src/test/java/com/ivy/ui/time/impl/MaterialDatePickerDateBridgeTest.kt`](shared/ui/core/src/test/java/com/ivy/ui/time/impl/MaterialDatePickerDateBridgeTest.kt)
  — the zone-boundary suite (new).

Storage semantics are untouched. The write path still goes through
`TimeConverter.LocalDateTime.toUTC()` (`updateDateTime` in
[`EditTransactionViewModel.kt:574`](feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionViewModel.kt:574),
[`LoanViewModel.kt:303`](feature/loans/src/main/java/com/ivy/loans/loan/LoanViewModel.kt:303),
[`LoanDetailsViewModel.kt:509`](feature/loans/src/main/java/com/ivy/loans/loandetails/LoanDetailsViewModel.kt:509))
and the read path still goes through `Instant.toLocalDate()`. The bridge only changes **which day the
picker pre-selects and which day it reports**, so no already-stored row is re-interpreted and no
existing transaction moves to another day. The change is additive for the picker (a `null` initial
date now means the user's local today instead of Material3's UTC today): `DateTimePicker`,
`TimeConverter` and `TimeProvider` public APIs are unchanged, no Hilt binding was added, and nothing
was renamed or reformatted.

---

## 4. Do not relitigate

* **`TimeConverter` was already zone-aware and was NOT rewritten.** `StandardTimeConverter`
  (`shared/base/.../impl/StandardTimeConverter.kt`) resolves the day with
  `timeProvider.getZoneId()`. It is correct; the defect was in how a call site used it. Do not
  "fix" it again.
* The defect is **not** a wrong zone constant, and it is **not** `ZoneId.systemDefault()` in the
  picker (the picker never used the ambient zone — it used the injected `TimeConverter`, which was
  the problem, because Material3's millis are not instants).
* **One app build happens in a later phase.** This phase deliberately ran no `:app:assembleDemo` and
  no `:app:compileDemoKotlin`.

---

## 5. Test strategy: pin the zones, never the ambient one

The suite injects its zones explicitly and never touches `ZoneId.systemDefault()`, so it fails on a
developer machine in **any** time zone when the bridge is wrong. Zones pinned: **Asia/Jerusalem**
(the machine's own zone, UTC+3 in the dates used), **UTC-11** (negative offset), **Asia/Kolkata**
(UTC+5:30 — issue 3424's reporter), **Asia/Tokyo** (UTC+9 — issue 3646's reporter) and **UTC**. The
`TimeProvider` is a fixed-zone/fixed-instant implementation and the converter is the **real**
`StandardTimeConverter` — not a mock — so the test exercises production plumbing.

Two traps it avoids on purpose:

* A pure `pickedDate(initialSelectedDateMillis(x)) == x` round trip **cannot** catch this bug: the
  broken legs are self-consistent (both misinterpreted the same way). The suite therefore asserts the
  **wire encoding** (`initialSelectedDateMillis` must equal the UTC midnight of the app-zone day,
  [`MaterialDatePickerDateBridgeTest.kt:105`](shared/ui/core/src/test/java/com/ivy/ui/time/impl/MaterialDatePickerDateBridgeTest.kt:105)).
* **DST.** The first draft hard-coded "Jerusalem is UTC+3" on a 2024-03-10 instant; Israel was UTC+2
  until 2024-03-29, so the test's *expectation* was wrong even though the fix was right. The dates are
  now DST-safe and the offsets the suite relies on are asserted in
  `the zones used by this suite really have the offsets it claims`.

---

## 6. Evidence

Test names, all in `com.ivy.ui.time.impl.MaterialDatePickerDateBridgeTest`:

1. `the zones used by this suite really have the offsets it claims`
2. `initial date is the app zone day of the instant, not its UTC day`
3. `initial date defaults to the local today, not the UTC today`
4. `picked date is stable in a negative offset zone`
5. `picked date is stable in a positive offset zone`
6. `the day handed to Material3 is the app zone day encoded as UTC midnight`
7. `no date picked means no date reported`

### Red run (before the fix, against the final test file)

Command: `:shared:ui:core:testDebugUnitTest --tests "*MaterialDatePickerDateBridgeTest*"`, log
`C:\ivy-artifacts\phase4-red-final.log`:

```
MaterialDatePickerDateBridgeTest > picked date is stable in a negative offset zone FAILED
MaterialDatePickerDateBridgeTest > initial date is the app zone day of the instant, not its UTC day FAILED
MaterialDatePickerDateBridgeTest > the day handed to Material3 is the app zone day encoded as UTC midnight FAILED
MaterialDatePickerDateBridgeTest > initial date defaults to the local today, not the UTC today FAILED
7 tests completed, 4 failed
BUILD FAILED in 2m 14s
```

with the actual-vs-expected dates, `shared/ui/core/build/test-results/testDebugUnitTest/TEST-com.ivy.ui.time.impl.MaterialDatePickerDateBridgeTest.xml`:

| Test | Expected | Actual |
|---|---|---|
| `picked date is stable in a negative offset zone` | `2024-04-11` | `2024-04-10` |
| `initial date is the app zone day of the instant, not its UTC day` | `1712793600000L` (= 2024-04-11T00:00Z) | `1712784600000L` (= 2024-04-10T21:30Z) |
| `initial date defaults to the local today, not the UTC today` | `1712793600000L` | `1712784600000L` |
| `the day handed to Material3 is the app zone day encoded as UTC midnight` | `1712793600000L` | `1712784600000L` |

(Earlier runs kept as `C:\ivy-artifacts\phase4-red.log` — `6 tests completed, 3 failed` — and
`C:\ivy-artifacts\phase4-red2.log` — `6 tests completed, 4 failed`; `phase4-red-final.log` is the run
that matches the committed test file.)

### Green run (after the fix)

Command: `:shared:ui:core:testDebugUnitTest` (the whole module task), log
`C:\ivy-artifacts\phase4-green.log`:

```
BUILD SUCCESSFUL in 4m 57s
```

JUnit XML, read straight from `shared/ui/core/build/test-results/testDebugUnitTest/`:

| File | `<testsuite>` attributes |
|---|---|
| `TEST-com.ivy.ui.time.impl.MaterialDatePickerDateBridgeTest.xml` | `tests="7" skipped="0" failures="0" errors="0"` |
| `TEST-com.ivy.ui.time.impl.IvyTimeFormatterTest.xml` | `tests="39" skipped="0" failures="0" errors="0"` |
| `TEST-com.ivy.ui.FormatMoneyUseCaseTest.xml` | `tests="10" skipped="0" failures="0" errors="0"` |
| `TEST-com.ivy.ui.component.OpenSourceCardPaparazziTest.xml` | `tests="2" skipped="0" failures="0" errors="0"` |

→ **58 tests, 0 failures, 0 errors** in `:shared:ui:core` (51 before this phase + 7 new).
`shared/data/core` (182/0) and `feature/shopping` (32/0) were **not** touched and were not re-run;
`shared/base` was not touched either. No module other than `:shared:ui:core` was compiled, and no
`:app:*` task was run.

---

## 7. Cross-check: Phase 3 backup timestamps (verdict: deliberately different, no change)

`BackupsViewModel` renders a row with `BackupDisplayFormat.timestamp(...)`
([`BackupsViewModel.kt:154`](feature/backup/src/main/java/com/ivy/backup/BackupsViewModel.kt:154)),
which formats `Instant.ofEpochMilli(epochMillis)` as `yyyy-MM-dd HH:mm` in
`ZoneId.systemDefault()` ([`BackupDisplayFormat.kt:37`](shared/data/core/src/main/java/com/ivy/data/backup/local/BackupDisplayFormat.kt:37)).
**This is not the same defect:**

* it renders a **timestamp** (an event: date *and* wall-clock time), not a date-only value, so there
  is no "which midnight is the day" question to get wrong;
* the value is an absolute instant that is **never round-tripped back into storage** — the epoch
  millis come from the file name and are written once; nothing is decoded back into a day;
* rendering in the device's zone is exactly the intent: the user sees the wall-clock time at which
  they were backed up.

One nit, deliberately left alone: it reads the ambient `ZoneId.systemDefault()` instead of
`TimeProvider.getZoneId()`. In production those are the same zone, and plumbing `TimeProvider` into a
pure, Android-free data-layer object would be a bigger change than the bug justifies. It is recorded
here so it is a decision, not an oversight.

---

## 8. Date-plumbing inventory (for the next phase)

Zone-aware, correct, **not changed**:

* [`StandardTimeConverter.kt`](shared/base/src/main/java/com/ivy/base/time/impl/StandardTimeConverter.kt:19) — `Instant.toLocalDateTime/toLocalDate/toLocalTime` in `TimeProvider.getZoneId()`, `LocalDateTime.toUTC()` back.
* [`TimeConverter.kt`](shared/base/src/main/java/com/ivy/base/time/TimeConverter.kt:8) — the interface.
* [`DeviceTimeProvider.kt:14`](shared/base/src/main/java/com/ivy/base/time/impl/DeviceTimeProvider.kt:14) — the one place where `ZoneId.systemDefault()` is correct.
* [`IvyTimeFormatter.kt:72`/`:78`](shared/ui/core/src/main/java/com/ivy/ui/time/impl/IvyTimeFormatter.kt:72) — `formatLocal` (app zone) and `formatUtc` (UTC; no production call site).
* [`RootActivity.kt`](app/src/main/java/com/ivy/wallet/RootActivity.kt:172) — the legacy `MaterialDatePicker` path already uses `LocalDate.toEpochDay() * MILLISECONDS_IN_DAY` (UTC-day) and `LocalDate.ofEpochDay(it / MILLISECONDS_IN_DAY)`, so it is consistent with Material3's contract and needs no change.

Latent instances of the same *class* (found, **not** changed — each would shift behaviour of data or
filters that already exist, and none of them is reached by the five issues):

* `temp/legacy-code/.../utils/DateExt.kt:28,36` — `timeNowUTC()`, `dateNowUTC()`, and the deprecated
  `convertLocalToUTC`/`convertUTCtoLocal` family (line 207+). Used for "today"/"this year" in period
  selectors (`HomeViewModel.kt:514`, `TransactionsViewModel.kt:701`, `BalanceViewModel.kt:97`,
  `PieChartStatisticViewModel.kt:236`) and in `FromToTimeRange.kt:81,113`
  (`todayStartOfDayUTC`). That is Ivy's deliberate UTC-day convention for period boundaries; changing
  it would silently re-bucket existing transactions, so it is out of scope for this phase.
* `temp/legacy-code/.../components/DateTimeRow.kt:57-75` — `getTrueDate()`, labelled by its own author
  "The timepicker returns time in UTC, but the date picker returns date in LocalTimeZone", deprecated,
  and no longer used by the edit-transaction flow.
* `feature/import-data/.../ParseFields.kt:117` — `.atStartOfDay()` when parsing an imported CSV date.
* `feature/reports/ReportViewModel.kt:290,305` — `ZoneId.systemDefault()` directly instead of the
  injected `TimeProvider` (same zone in production).
* `shared/base/.../TimeConversionExt.kt:9` — deprecated `Instant.convertToLocal()` (ambient zone).
* `shared/base/.../legacy/DateTimeUtil.kt:16,20` — deprecated `epochMilliToDateTime()`/`toEpochMilli()`
  (UTC), used by the Room type converters (`RoomTypeConverters.kt:18,55`): this is the storage
  convention itself, and deliberately unchanged.

---

## 9. What is NOT verified

* **No APK, no emulator, no device run.** There is still no APK containing templates, checkout,
  hierarchy, backups or this fix, and the fix has **never been observed in the UI**. The on-device
  date verification belongs to a later phase.
* The change is **JVM-test-verified only**: 7 new unit tests (58 in `:shared:ui:core`, 0 failures) plus
  the 51 pre-existing tests in that module. Nothing was re-run in `shared/data/core`,
  `feature/shopping` or any feature module, and `:app:*` was not built (no Hilt binding was added, so
  no app compile is required by this change).
* The claim "the picker now behaves correctly on a device" is **not** made. What is verified is the
  conversion at the Material3 boundary, in injected zones, on the JVM.
* Nothing was pushed to upstream; `upstream`'s push URL is still `DISABLED_NO_PUSH_TO_UPSTREAM`.
