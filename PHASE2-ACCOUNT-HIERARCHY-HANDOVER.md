# Account hierarchy (Phase 2): state and decisions

This note exists so the work is not trapped in one conversation. Read this before continuing.
It follows the convention of `SHOPPING-HANDOVER.md`.

## What Phase 2 is

Accounts can have an optional `parentAccountId`, so accounts form a tree. A child rolls up into its
parent's balance; the hierarchy is shown as an indented tree with rolled-up balances and a grand total.

`accounts.parentAccountId` already existed in migration 132 before this phase, so **Phase 2 changed no
schema**: migration 132 is untouched, no 133 was added, and the pair of columns is nullable on purpose
(Room only accepts a SQL default when the entity declares `@ColumnInfo(defaultValue = ...)`, so an
undeclared default is a runtime schema mismatch).

## Decisions already made, do not relitigate

**The rules live in pure code.** `com.ivy.data.hierarchy.AccountHierarchy` (`shared/data/core`) knows
nothing about Android, Room, Compose or Ivy's money formatting. It works on `HierarchyNode` = id +
optional parent id + the account's **own** balance, so every rule is unit-testable on the JVM. The UI
and the view model only load data, format money and render.

**The double-count rule.** The grand total is the sum of the ROLLED-UP balances of the ROOT accounts
only. A parent's own balance is never counted twice, and a child is never added on its own, because the
child is already inside its parent's rolled-up figure. `grandTotal` implements exactly this;
`rootIds` is what it folds over. This is the rule the phase exists to protect, and it is enforced by a
test (see below).

**Roll-up is recursive, any depth.** `rolledUpBalances` memoises a depth-first sum of an account's own
balance plus every descendant's, so a grandchild is inside its parent and its grandparent.

**Cycles and self-parents are rejected, and cannot be created through the UI.** The parent picker only
offers `parentCandidates` = every account except the account itself and all of its own descendants, so
selecting one cannot close a loop. `validateParentAssignment` is the backstop behind the picker, and it
is also called on save in `AccountCreator.checkedParent`.

**Malformed data must not hang anything.** `effectiveParents` treats three cases as "no parent": a
genuine root, a child whose parent id no longer resolves (**orphan tolerance** - the money is neither
lost nor crashed on), and an account naming itself as its parent. A stored multi-account cycle is cut
deterministically at the smallest id, so roll-up and the tree never loop forever.

**Assigning a parent happens in two places, and the editor is one of them.** The Material3 hierarchy
screen has an edit action per row that opens the parent picker (with "No parent" to clear it). The
legacy account create/edit modal (`AccountModal`) also carries the parent field, fed by the account
editor screen (`feature/transactions`). Both reuse the same pure `parentCandidates`, so neither can
offer an illegal parent.

**The parent id must survive every conversion.** `AccountRepository.save` writes whole entities with an
upsert, so any mapper that dropped `parentAccountId` would silently wipe the hierarchy on the next edit.
That is why the field is carried through `AccountMapper` (domain <-> entity), the legacy
`com.ivy.legacy.datamodel.Account` (`toEntity` / `toDomainAccount`) and `CreateAccountData`.

**The entry point is the More hub.** The hierarchy screen is reachable from `HomeMoreMenu` next to the
shopping entry, with its own drawable (`home_more_menu_accounts_hierarchy`) and strings, registered in
`shared/ui/navigation/.../Screens.kt` (`AccountHierarchyScreen`) and wired in
`app/src/main/java/com/ivy/IvyNavGraph.kt`.

**Money is `FormatMoneyUseCase`.** Amounts are `BigDecimal` throughout (`calculate` -> roll-up ->
total); the only place a `Double` appears is Ivy's formatter, which takes one. No `String.format`, no
`Double` arithmetic.

## The test that proves the double-count rule

`TEST-com.ivy.data.hierarchy.AccountHierarchyTest.xml` -> `tests="14" failures="0" errors="0"`.

`AccountHierarchyTest.the grand total is the rolled up balances of the roots only and never counts a
parent twice` is the Phase 2 proof obligation: it asserts the total is 130 (parent 100 + child 30) and
explicitly asserts that the naive "sum every account's shown balance" alternative, 160, is NOT the
total. If someone changed the total to sum every account, that test fails.

## Evidence

| what | result |
| --- | --- |
| `:shared:data:core:testDebugUnitTest :shared:data:core:assembleDebug :shared:ui:core:assembleDebug :shared:ui:navigation:assembleDebug :feature:accounts:assembleDebug :feature:home:assembleDebug :temp:legacy-code:assembleDebug :feature:transactions:assembleDebug` | `BUILD SUCCESSFUL in 3m 37s` (`C:\ivy-artifacts\phase2b-verify.log`) |
| unit tests in `:shared:data:core` | 153 tests, 0 failures, 0 errors across 14 suites; the hierarchy suite is 14/0/0 |
| `:app:compileDemoKotlin` (compiles the app module and its aggregated Dagger/Hilt component) | `BUILD SUCCESSFUL in 12m 51s` (`C:\ivy-artifacts\phase2b-appcompile.log`) |
| migration 132 / 133 | 132 unchanged, no 133 |

## What is NOT verified

No APK contains the hierarchy feature and **nothing here is device-verified**: there is no emulator
run, no `:app:assembleDemo`, and no runtime proof that the screen renders or that a parent survives a
real edit round-trip. `:app:compileDemoKotlin` is app-level evidence, but it is a compile, not a run.

## Rules for working here

- Debug artefacts go to `C:\ivy-artifacts`, never into the repo.
- Verify with a build before claiming anything works.
- `upstream` is fetch-only with pushes disabled on purpose. Push only to `origin`.

## Tier 1 follow-up: currency safety

Migration 132 stays frozen. No column, entity or migration was added; the screen reads the existing
`accounts.asset` and `exchange_rates` rows.

Pure rules live in `shared/data/core/src/main/java/com/ivy/data/hierarchy/HierarchyCurrency.kt`
(`RateTable`, `valorise`, `currencySubtotals`, `baseRolledUpBalances`, `baseGrandTotal`,
`accountsExcludedFromBaseTotal`) and are tested by `HierarchyCurrencyTest` (15 tests).

### Two questions, two numbers - never one

- **Per-currency subtotals are native.** `currencySubtotals` groups each account by its OWN currency
  and sums its OWN balance. Own balances are disjoint, so this never double counts a child - it
  answers "how much do I hold in EUR", not "what is the tree worth". Every subtotal is labelled with
  its currency code (e.g. `EUR 50.00`).
- **Rolled-up and grand-total figures are base currency.** Each account's own balance is converted
  through `RateTable`, then the existing roll-up runs. The screen labels the grand total and every
  rolled-up row explicitly as base currency (`Rolled up: 1,300.00 USD · USD base`, and the note
  "Rolled-up and total figures are in USD (base currency).").
- **The double-count rule is unchanged.** `baseGrandTotal` is still the sum of the ROOT accounts'
  rolled-up balances; a child is inside its parent's figure and a parent is never added twice.
  `HierarchyCurrencyTest` asserts 130 for parent 100 + child 30 and explicitly asserts that the naive
  160 is NOT the total, so either regression fails a test.
- **An excluded parent does not hide a usable child.** The roll-up walks through an account that has
  no rate, so a USD child of a EUR parent is still counted in the base total.

### Missing and stale rates - the definition

`exchange_rates` stores `(baseCurrency, currency, rate, manualOverride)` and **no timestamp**. The
existing model therefore cannot express the age of a rate, so it cannot age one out. The only trust
signals it carries are:

1. the row exists for the pair, or for the reciprocal pair (1 EUR = 1.25 USD <=> 1 USD = 0.8 EUR);
2. the value survives the same rule the rest of the app uses (`PositiveDouble` / `validateRate`):
   finite and strictly greater than zero.

**Stale, in this model, therefore means "not usable as a price":** no row, a zero/negative/NaN/
infinite value, or a row whose base/currency pair is unrelated to the question. Missing and stale are
deliberately the same outcome, because the model cannot tell them apart. `manualOverride` rows are
trusted like any other: a user-entered rate is deliberate, and there is no timestamp with which to
call it old.

Consequences, all tested:

- such an account is **excluded** from every base-currency figure - never counted as 0, never
  converted at 1:1;
- its row shows the marker **"no rate"**;
- its currency subtotal keeps the native amount (that money is real) but says
  **"no rate · excluded from base total"** and contributes nothing to the base total;
- the screen states how many accounts were excluded, so a total is never silently partial.

If a real age ever becomes available, the honest place to add it is `RateTable`/`RateRow` (a timestamp
plus a horizon). That would need migration 133, which this batch deliberately did not add.

### Rendering

`AccountHierarchyScreen` shows, under the grand total: the base-currency note, the per-currency
subtotals, the excluded-accounts note, then the tree. Rows show the native own balance with its
currency code; a parent's rolled-up figure is separately labelled as base; an account without a rate
shows "no rate" instead of a rolled-up number. All of it uses `UI.colors`/`UI.typo` and the existing
Ivy components - the Material3 sweep of this screen was not undone.
