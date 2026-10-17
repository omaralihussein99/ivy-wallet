# Design pass — part 1 handover (dependency plumbing + account hierarchy + backups)

Status: **compiles, unit tests green, visual parity NOT verified.**
No app build (`:app:assembleDemo`) and no screenshots were produced in this part; visual parity is
deferred to the app build that follows the full pass.

## 0. Reconciliation performed before any edit

- `git status --short --untracked-files=all` was **empty** (clean tree).
- `git log --oneline -4`:
  - `4def39d fix(shopping): open a new trip with its items, editable item prices, one-transaction checkout`
  - `9bab8e7 docs: correct three file:line references in the Phase 6 handover`
  - `9a8e52f docs: Phase 6 handover for the scheduled backup worker fix`
  - `b42f8de fix(backup): make the scheduled LocalBackupWorker constructible by Hilt`
- HEAD `4def39d` **is** the shopping-fix commit. There were no leftover dirty paths inside
  `feature/shopping` (or anywhere else), so nothing had to be left alone.
- No Gradle/Java build was running. `java.exe` processes present were only the Gradle 8.8 daemon and
  the Kotlin compile daemon (idle); no build actions were in flight.

## 1. Diagnosis

The new screens were written against **Material3 defaults** (`Scaffold`, `TopAppBar`, `AlertDialog`,
`HorizontalDivider`, `IconButton`, `TextButton`, `OutlinedTextField`), while Ivy's visual language is
`UI.colors` / `UI.typo` / `UI.shapes` plus the components in `temp:old-design` and
`temp:legacy-code`. Material3 is only the wrapper underneath (`IvyTheme` installs a Material3 theme),
so M3 defaults do not match Ivy screens.

Fix: rebuild the screens on Ivy's own components and keep Material3 **only** as the `Text`
primitive that carries a `UI.typo.*.style(...)`.

## 2. Dependency plumbing

Because the deprecated Ivy components expose `com.ivy.base.legacy.Theme` /
`com.ivy.domain.RootScreen` in their public signatures, and the temp modules declare them as
`implementation` (not `api`), each consuming feature module needs them directly. Added, mirroring
`feature/categories/build.gradle.kts`:

- `feature/shopping/build.gradle.kts`: `projects.shared.base`, `projects.shared.domain`,
  `projects.temp.legacyCode`, `projects.temp.oldDesign`
- `feature/backup/build.gradle.kts`: same four

No new module was created and `compose-material3` was not removed (it comes from the build
convention and the design system itself depends on it).

## 3. Component mapping — account hierarchy

| Before (Material3) | After (Ivy) |
| --- | --- |
| `Scaffold` + `TopAppBar` + `BackButton` | `BoxWithConstraints` + sticky `IvyToolbar(onBack = ...)` (`LazyColumn.stickyHeader`) |
| `<no background>` on non-legacy destination | root `Modifier.fillMaxSize().background(UI.colors.pure)` (`includeSurface = false` for this screen) |
| `MaterialTheme.typography.labelLarge` | `UI.typo.c` + `UI.colors.gray`, uppercased label |
| `MaterialTheme.typography.titleLarge` | `UI.typo.nH1` 30sp ExtraBold + currency `nH1` normal (the `BalanceRow`/`AccountsTab` treatment) |
| `HorizontalDivider()` | `TransactionsDividerLine(paddingHorizontal = 24.dp)` (2dp `UI.colors.medium`) |
| `IconButton` + `Icons.Filled.KeyboardArrowDown/Right` | `IvyIcon(R.drawable.ic_expandarrow)` rotated 180° when expanded, in a 48dp clickable circle; leaves keep a 48dp spacer |
| `IconButton` + `Icons.Filled.Edit` | `IvyIcon(R.drawable.ic_edit)` in a 48dp clickable circle, `UI.colors.gray` |
| `titleMedium` name | `UI.typo.b2` Bold `UI.colors.pureInverse` |
| `bodySmall` parent caption | `UI.typo.nC` `UI.colors.gray` |
| `bodyMedium` own balance | `UI.typo.nB2` `UI.colors.gray` |
| `titleSmall` bold rolled-up | `UI.typo.nB1` Bold `UI.colors.primary` (own vs rolled-up stays visually distinct) |
| `AlertDialog` parent picker | `IvyModal` + `ModalTitle` + `ParentPickerOption` rows + `ModalSet(label = Close)` |
| `MaterialTheme.colorScheme.error` | `UI.colors.red` (`nB2`) |

Preserved exactly: `(12 + depth * 20).dp` indentation, roots-only grand total, roll-up arithmetic,
cycle rejection (validator stays the backstop; the error text is still shown), orphan tolerance,
and "No parent" as the always-present first option.

## 4. Component mapping — backups

| Before (Material3) | After (Ivy) |
| --- | --- |
| `Scaffold` + `TopAppBar` + `TextButton` actions | `BoxWithConstraints` + sticky `IvyToolbar`, refresh as `CircleButtonFilled(R.drawable.ic_refresh)` |
| `<no background>` on non-legacy destination | root `background(UI.colors.pure)` |
| no page title | `UI.typo.h2` title, matching `SettingsScreen` |
| "Back up now" in the toolbar | `BackBottomBar { IvyButton(text = ..., iconStart = ic_save, enabled = !isBusy) }` |
| `HorizontalDivider` row separators | Settings-style cards: 16dp gutters, `UI.shapes.r4`, 2dp `UI.colors.medium` border, 12dp gaps |
| `titleMedium` timestamp | `UI.typo.b2` Bold `UI.colors.pureInverse` |
| `bodySmall` `size · filename` | `UI.typo.nB2` gray 14sp |
| `TextButton` restore + `IconButton` delete | whole card clickable → restore (gated on `!isBusy`), trailing `DeleteButton()` |
| `bodySmall` captions | `UI.typo.nC` `UI.colors.gray` |
| `bodyMedium` message banner | `UI.typo.nB2` `UI.colors.primary` / `UI.colors.red` |
| `AlertDialog` + `OutlinedTextField` | `IvyModal` + red `b1` title + `b2` warning + `IvyNameTextField` + `ModalNegativeButton(enabled = restoreConfirmed)` |
| `MaterialTheme.colorScheme.*` | `UI.colors.*` only |

Preserved exactly: list ordering, size/filename rendering, the typed gate
(`confirmationText.trim() == fileName`, disabled otherwise), the `busy` guard, and the existing
messages.

## 5. Decisions taken

- `IvyModal` replaces both the M3 `AlertDialog` (parent picker) and the M3 restore dialog. Neither
  modal contains a `LazyColumn`: `IvyModal` applies `verticalScroll` itself, so nested lazy lists
  would get infinite height. Options are rendered with `forEach` inside the modal column.
- Each `IvyModal` gets a **stable** `remember { UUID.randomUUID() }` id so back handlers are not
  leaked by recomposition. Only one modal is composed at a time on each screen.
- `BackBottomBar` / `IvyModal` are scope-bound (`BoxWithConstraintsScope` / `BoxScope`), so the
  screen entry points became `BoxWithConstraintsScope` extensions (they are invoked from
  `IvyNavGraph`, which already has that receiver). No new shell was invented.
- `DeleteButton` has no `enabled` parameter; the busy state is applied with
  `Modifier.alpha(...)` plus a guarded click instead of adding a parameter to the legacy component.
- The grand total is already formatted by the view model, so it is rendered with the `BalanceRow`
  30sp treatment rather than being passed to `BalanceRow` (which takes a `Double`). This keeps the
  money formatting in one place (`FormatMoneyUseCase`).
- English only. Arabic (`values-ar`) and `android:supportsRtl` were deliberately not touched, per the
  owner's decision for this pass. `account_hierarchy_entry` keeps its key but now reads
  "Account hierarchy"; the More-hub "Shopping" label now comes from `R.string.shopping`.

## 6. What remains (next part)

- Restyle the shopping screens (`ShoppingListsScreen`, `ShoppingListScreen` and their sheets) the
  same way. Their module now already has the required dependencies.
- Apply the same mapping to any other new screen still using Material3 shells.
- App build + screenshots (`:app:assembleDemo`, then emulator) to confirm visual parity and to check
  dark/AMOLED rendering, toolbar gradients and the bottom bar over the navigation inset.
- Localisation of the new strings (Arabic) is a separate, later decision.
- The legacy account editor (`AccountModal.kt` `ChooseParentModal`) still uses its M3 `AlertDialog`
  shell; it was explicitly out of scope and was not touched.

## 7. What is NOT verified

- **Visual parity is not verified.** No app build and no screenshots were produced in this part.
- No device/emulator run; behaviour of the two modals, the back-handler stack and the keyboard
  shifting is verified only by compilation and by reading the legacy component implementations.
- `feature/accounts` and `feature/home` contain Paparazzi screenshot suites; they were not run in
  this pass (Paparazzi needs a supported graphics environment and is unrelated to the two new
  screens). The only unit tests executed were `:feature:shopping:testDebugUnitTest` (51 tests), used
  to prove the dependency addition did not break a consuming module.
- `feature/backup` has no JVM unit tests at all (only the shared backup logic in
  `shared/data/core`, which was not modified).
