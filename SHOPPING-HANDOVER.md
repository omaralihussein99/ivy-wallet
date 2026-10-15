# Shopping list: state and next steps

This note exists so the work is not trapped in one conversation. Read this before continuing.

## Where things stand

Three commits on `origin` (`omaralihussein99/ivy-wallet`):

| commit | what it is | verified by |
| --- | --- | --- |
| `efe4d6e` | shopping list feature (lists + items + two Material3 screens + More menu entry), migration 130 to 131 | built, installed on an emulator, migration ran against a real database |
| `dc3c835` | checkout rules + 16 unit tests, `isTemplate` added to migration 132 | 16 tests, 0 failures, 0 errors |
| `c859001` | checkout service that posts a trip to the ledger, `findTemplates` / `findTrips` | `:feature:shopping:assembleDebug`, zero errors |

The app-level APK has **not** been rebuilt since any of this. There is no installable build
containing templates or checkout yet.

## Decisions already made, do not relitigate

**A list is either a template or a trip.** A template is the reusable original ("Weekly groceries"):
items, no state, never ticked, never posted. A trip is a working copy of a template that you tick off
in the shop and then archive. `shopping_lists.isTemplate` distinguishes them; rows created before
templates existed have NULL, which means trip.

**Ivy has no multi-line transaction.** A repo-wide search for `splitId`, `SplitPart`, `isSplit`,
`splitParts` returns nothing, and `TransactionEntity` has no grouping column. So "one big transaction
with a box per item" is impossible. A trip becomes **one expense row per item**, written together with
the same account and the trip name as the title. This is the only shape that preserves each item's own
price and its own category, which is what keeps reports honest.

**Ticking never interrupts.** No price sheet pops up per tick - the tenth tick in a busy aisle would be
unbearable. Ticking is silent; one Checkout action reviews every ticked item at once. This also means
the single-item case is the multi-item case with one row, so there is one code path, not two.

**Unpriced items are skipped, never posted as zero.** A zero-amount ledger row is a lie. This is
covered by a test.

**Migration 132 is not yet shipped, so it can still be extended.** Do not add a 133 for something that
belongs in 132. Once a build ships, 132 is frozen.

**Nullable columns, deliberately.** Room only accepts a SQL column default when the entity declares
`@ColumnInfo(defaultValue = ...)`; an undeclared default is a schema mismatch that fails at runtime.
So added columns are nullable and null carries the meaning.

## Next, in order

1. **Wire `ShoppingCheckoutService` into `ShoppingListDetailViewModel`** and build the checkout sheet:
   one box per ticked item (price, account, category), pre-filled from the item and the list, with a
   running total. This is the shortest path to something usable.
2. **Template and trip sections** in `ShoppingListsViewModel` / `ShoppingListsScreen`, plus
   "new trip from template" (copy the template's items into a new trip).
3. **Money formatting**: the shopping screens use `String.format`, which ignores Ivy's decimal and
   grouping settings. Replace with `FormatMoneyUseCase` from `shared/ui/core`.
4. **Account hierarchy**: `accounts.parentAccountId` already exists in 132. Needs a Material3 screen to
   assign parents and roll-ups with the double-count rule (a child rolls into its parent; the parent's
   own balance is never counted twice).
5. **Local automatic backups**: scheduled copy with rotation, a list of backups, and restore.
6. **The five date off-by-one bugs** (#3646, #3495, #3383, #3424, #3231). `TimeConverter` is already
   zone-aware in production, so the cause is elsewhere - in whatever converts a picked date back to an
   `Instant`, or in how it is converted back for display.
7. **One app build**, then install and verify on the emulator before believing any of it.

## Rules for working here

- The full write-up of the reasoning is in the conversation that produced these commits. The commits
  themselves are the durable record.
- Verify with a build before claiming anything works. A wrong import (`PositiveValue` is in
  `com.ivy.data.model`, not `.model.primitive`) was caught by the compiler and nowhere else.
- Debug artefacts go to `C:\ivy-artifacts`, never into the repo.
- `upstream` is fetch-only with pushes disabled on purpose. Push only to `origin`.
