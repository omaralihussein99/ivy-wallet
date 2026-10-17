# Design pass — handover (part 1: plumbing + hierarchy + backups, part 2: shopping)

Status: **compiles, unit tests green, visual parity NOT verified.**
No app build (`:app:assembleDemo`) and no screenshots were produced in either part; visual parity is
deferred to the app build that follows the full pass. Part 2 (the three shopping screens) is in
section 8.

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

## 8. Part 2 — the three shopping screens

Starting point: `4f0f3ce` (this document), tree clean. `4def39d` was read in full before any edit
and none of its behaviour was reverted (see 8.6).

Only two files changed: `feature/shopping/src/main/java/com/ivy/shopping/ShoppingListsScreen.kt` and
`.../ShoppingListDetailScreen.kt`. No view model, no rule, no service, no string, no test was touched.

### 8.1 Screen 1 — shopping lists (`ShoppingListsScreen.kt`)

| Before (Material3) | After (Ivy) |
| --- | --- |
| `Scaffold` + `TopAppBar` + `BackButton` | no shell; root `BoxWithConstraints(fillMaxSize().background(UI.colors.pure))`, title as the first list item |
| — | `Text(stringResource(shopping_lists))` in `UI.typo.h2` ExtraBold `pureInverse`, 32dp top / 24dp start (the `CategoriesScreen` treatment) |
| `FloatingActionButton` + `Icons.Filled.Add` | `BackBottomBar { IvyButton(text = "New shopping list", iconStart = ic_plus) }` (back is the bar's own circle button) |
| back navigation from the toolbar | `BackBottomBar(onBack = ...)` |
| `HorizontalDivider` between rows | settings-style cards: `padding(horizontal = 16.dp)`, `clip(UI.shapes.r4)`, `border(2.dp, UI.colors.medium, UI.shapes.r4)`, 16dp gaps between cards |
| `labelLarge` primary section header | `SettingsSectionDivider` recipe: 32dp spacer + `UI.typo.b2` Bold `UI.colors.gray` at 32dp start + 16dp |
| `titleMedium` row title | `UI.typo.b1` ExtraBold `pureInverse`, `maxLines = 1` + ellipsis |
| `bodySmall` subtitle | `UI.typo.nB2` Normal `UI.colors.gray` at 14.sp, `maxLines = 1` + ellipsis |
| `IconButton` + `Icons.Filled.PlayArrow` | `CircleButtonFilled(icon = ic_arrow_right, contentDescription = new_trip_from_template)` |
| `IconButton` + `Icons.Filled.Delete` | `DeleteButton()` |
| `AlertDialog` + `OutlinedTextField` + `Switch` + `TextButton`s | `IvyModal` + `ModalTitle` + `IvyNameTextField` (its own underline) + a settings-style row with `IvySwitch` + `ModalAdd` |
| `bodyLarge`/`onSurfaceVariant` empty state | `UI.typo.b2` Medium `UI.colors.gray`, centred |
| 88dp tail spacer (for the FAB) | 150dp tail spacer (there is a bottom bar now) |
| plain `LazyColumn` | `rememberScrollPositionListState(key = "shopping_lists_lazy_column")`, parity with Categories/Accounts |

Kept verbatim: the item keys (`it.id`, `templates_header`, `trips_header`), the templates/trips split,
the subtitle composition ("n of m items  ·  remaining"), the start-trip-only-for-templates rule, and
the click-target fix — the open action is still an inner `Column(Modifier.weight(1f).clickable(...))`,
never the whole card, so one tap cannot open the template *and* start a trip (the original comment is
preserved). The modal keeps a stable `remember { UUID.randomUUID() }` id.

### 8.2 Screen 2 — trip/template detail (`ShoppingListDetailScreen.kt`)

| Before (Material3) | After (Ivy) |
| --- | --- |
| `Scaffold` + `TopAppBar` (name + `TextButton` Checkout + `IconButton` Delete) | `LazyColumn` with a sticky `IvyToolbar(onBack = ...)`: `UI.typo.b1` ExtraBold name (`maxLines = 1`, ellipsis, `weight(1f)`) and `DeleteButton()` trailing |
| Checkout as a toolbar `TextButton`, hidden unless `canCheckout` | `BackBottomBar { IvyButton(text = "Checkout", iconStart = ic_check, enabled = viewState.canCheckout) }` — always visible, disabled like every other Ivy primary action (two pill actions do not fit one toolbar) |
| `labelMedium`/`bodyMedium` header | `UI.typo.c` Bold uppercase grey caption (template label), `UI.typo.nB2` grey progress line, `UI.typo.b1` ExtraBold `pureInverse` for "Estimated left: …" |
| `MaterialTheme.colorScheme.surfaceVariant`/`primary` progress track | the same local `ShoppingProgressBar`, recoloured to `UI.colors.medium` track / `UI.colors.primary` fill with `UI.shapes.rFull` (the app has no neutral progress component; the threshold-coloured `ProgressBar` was not used) |
| `OutlinedTextField` for a new item | `IvyChecklistTextField` with `ImeAction.Done` firing add, plus `CircleButtonFilled(ic_plus)` |
| `HorizontalDivider` | `IvyDividerLine()` (2dp `UI.colors.medium`), inset 24dp |
| `Checkbox` | `IvyCheckbox` (48dp touch target) |
| `bodyLarge` title | `UI.typo.b2`; when checked `UI.colors.gray` + `TextDecoration.LineThrough` |
| `bodySmall` detail | `UI.typo.nC` `UI.colors.gray` |
| `IconButton` + `Icons.Filled.Edit` | `CircleButton(icon = ic_edit)` |
| `IconButton` + `Icons.Filled.Delete` | `DeleteButton()` |
| `bodyMedium` empty/missing text | `UI.typo.nB2`/`b2` `UI.colors.gray` |

Kept verbatim: ticking is still the row's own click (and the checkbox's), never the editor — the
editor is only ever opened by `ic_edit`; a template still shows **no** checkbox at all
(`showCheckbox = !viewState.isTemplate`); the item keys are still `it.id`; the live values
(`itemsChecked`, `itemsTotal`, `progress`, `estimatedRemainingText`, `detailText`) and their single
formatter (`FormatMoneyUseCase`, inside the view model) are unchanged.

### 8.3 Item editor (part of screen 2)

| Before | After |
| --- | --- |
| `AlertDialog` + two `OutlinedTextField`s + `TextButton`s | `IvyModal` + `ModalTitle` + two `IvyNumberTextField`s inside `GradientIvy` pills with `UI.typo.c` captions (the `IntervalPickerRow` precedent) + `ModalSave(enabled = editor.canSave)` |

Kept: `KeyboardType.Decimal` on both fields, `ImeAction.Next` on price (moves focus down) and
`ImeAction.Done` on quantity (hides the keyboard), the `canSave` gate, and the "leave the price empty"
hint.

### 8.4 Checkout (part of screen 2)

| Before | After |
| --- | --- |
| `ModalBottomSheet` + `rememberModalBottomSheetState` | `IvyModal` with a stable `remember { UUID.randomUUID() }` id — the owner explicitly accepted losing drag-to-expand and swipe-down; dismissal is tap-outside / back / the built-in close |
| `LazyColumn` of rows inside the sheet | plain `Column` + `forEach`: `IvyModal` already applies `verticalScroll`, so a nested lazy list would be measured with an infinite height and crash |
| second stacked `AlertDialog` for the account/category picker | the **same modal's second content state** (`sheet.picker != null` switches the body and the primary action to `ModalSet(label = "Cancel")`), never a second `IvyModal` |
| `OutlinedButton` pair for the mode | selected = `IvyButton(backgroundGradient = GradientIvy)`, unselected = `IvyBorderButton(GradientIvy)`. `IvyOutlinedButton` was deliberately **not** used for that pair: it has no `enabled` parameter |
| `OutlinedTextField` per row with `isError` | `IvyNumberTextField` in a pill: `GradientIvy` when the row is priced, `Gradient.solid(UI.colors.medium)` when it will be skipped; text White/bold vs `pureInverse`, red "skipped" text, live line total in `UI.typo.nB2` |
| `labelMedium` "Total" + `titleMedium` amount | `UI.typo.c` Bold uppercase grey label + the `AmountCurrencyB1` treatment (`nB1`/`b1` 20sp bold `pureInverse`) on the already-formatted string |
| `Button` confirm | `ModalPositiveButton(text = "Post to ledger", iconStart = ic_check, enabled = sheet.canConfirm && !sheet.posting)` |
| `colorScheme.error` refusal | `UI.typo.nB2` `UI.colors.red` |
| per-row account/category `OutlinedButton`s | `IvyBorderButton`s (`wrapContentMode = false`, `weight(1f)`) |

Decision worth recording: the money strings arrive pre-formatted (`estimatedRemainingText`,
`totalText`, `lineTotalText`), and `AmountCurrencyB1` takes a `Double` and would re-format them. The
composable is therefore not called; its typography (20sp, bold, `pureInverse`) is applied to the text
the view model produced, exactly as part 1 did for the hierarchy grand total. Money formatting stays
in `FormatMoneyUseCase`.

Two scroll states are kept (one for the review, one for the picker) so opening the picker after
scrolling the review starts at the top instead of at the shared offset.

### 8.5 Imports

Both files now import exactly one Material3 symbol, `Text`, and there is no `Icons.*` import at all;
actions use the app's drawables (`ic_plus`, `ic_delete` via `DeleteButton`, `ic_edit`, `ic_check`,
`ic_arrow_right`). Everything else is `UI.colors` / `UI.typo` / `UI.shapes` and the
`temp:legacy-code` / `temp:old-design` components.

Scope plumbing: both entry points became `BoxWithConstraintsScope` extensions (part 1's pattern), so
`IvyNavGraph` — itself a `BoxWithConstraintsScope` receiver — calls them unchanged. `IvyModal`
usages are `BoxScope` extensions, which `BoxWithConstraintsScope` already is.

### 8.6 Behaviour preserved from `4def39d`

- One-transaction mode (default) still posts a single row with its items attached; "one row per item"
  is still the alternative.
- The trip still opens populated (`LaunchedEffect(listId) { viewModel.load(listId) }` untouched).
- Item prices and quantities are still editable, with the same parse/gate rules.
- Unpriced rows are skipped, never posted as zero, and the empty-total refusal still shows.
- The double-post guard (`posting`) still disables the confirm action.
- The `start-a-trip` button is still outside the row's click area.

### 8.7 Verification of part 2

- `:feature:shopping:assembleDebug` → `BUILD SUCCESSFUL in 40s`
  (`C:\ivy-artifacts\design2-compile.log`; only deprecation warnings from the intentionally reused
  legacy components).
- `:feature:shopping:testDebugUnitTest` → `BUILD SUCCESSFUL in 29s`; JUnit XML:
  `TEST-com.ivy.shopping.ShoppingCheckoutSheetRulesTest.xml` `tests="24" skipped="0" failures="0" errors="0"`,
  `TEST-com.ivy.shopping.ShoppingListRulesTest.xml` `tests="27" skipped="0" failures="0" errors="0"` — 51, the baseline.
- Grep: the only `androidx.compose.material` import left in either file is
  `androidx.compose.material3.Text`; no `Icons.*`; no `Scaffold`/`TopAppBar`/`AlertDialog`/
  `ModalBottomSheet`/`FloatingActionButton`/`OutlinedTextField`/`HorizontalDivider`/`TextButton`/
  `IconButton`/`Checkbox`/`Switch`/`MaterialTheme` in either file.

### 8.8 What part 2 does NOT verify

- No app build and no screenshots: visual parity with Categories/Settings is unproven.
- Dark / AMOLED rendering of `pure` (which is the whole point of the explicit background) is unproven.
- Modal runtime behaviour is unproven: the shared scroll state swap between the review and the picker,
  the keyboard shifting inside `IvyModal`, and the back-handler stack for the two modals were checked
  only by reading `IvyModal` and by compilation.
- `IvyChecklistTextField` is multi-line by design; on a long item name it will wrap where the old
  single-line `OutlinedTextField` did not. Not observed on a device.
- The taller cards (b1 title + 14sp subtitle + 16dp gaps) were not measured against a real screen.
- Arabic/RTL were not touched, per the owner's English-only decision.
