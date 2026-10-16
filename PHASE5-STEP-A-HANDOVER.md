# Phase 5, Step A: one ledger-row title shape

This note records how the one remaining inconsistency from
[`SHOPPING-HANDOVER.md`](SHOPPING-HANDOVER.md) was resolved. It does **not** change any of the frozen
design decisions 1-7 in that file; decision 2 is the one that settled this.

## The problem

`ShoppingCheckoutService` had two ways to reach the ledger, and they disagreed about the title:

| path | title | description | production call site |
| --- | --- | --- | --- |
| `postTrip(items, prices, accountId, categoryId)` -> `buildCheckoutLines` | item name | none | **none** |
| `postTripDrafts(items, drafts, priceText, tripName, ...)` -> `buildDraftLines` | trip name | item name | `ShoppingListDetailViewModel.confirmCheckout()` |

Decision 2 froze the trip name as the title, with the item kept in the row description, because a trip
has to read as one shopping trip in reports while each row still says what was bought.

## What was done

1. `ShoppingCheckoutService.postTrip()` was deleted. It had no production call site: the only caller of
   the service is `ShoppingListDetailViewModel` (`confirmCheckout`), and it calls `postTripDrafts`.
   The 16 tests in `ShoppingListRulesTest` called the pure helper `buildCheckoutLines` directly, never
   `postTrip`, so no test lost its entry point by the deletion - the helper it used went with it.
2. `buildCheckoutLines` was deleted from `ShoppingListRules.kt`. With `postTrip` gone it had no caller
   at all, so keeping it would have meant maintaining the item-titled shape purely for its own sake.
3. The five tests in `ShoppingListRulesTest` that exercised `buildCheckoutLines` were moved onto the
   surviving path (`checkoutDrafts` + `buildDraftLines`) through a small `draftLines` helper that drives
   it exactly the way the screen does: each price box is pre-filled from the stored unit price, and the
   price typed into the box is what posts. The assertions now check the corrected title (the trip name)
   and additionally that the item name survives in the description.
4. `the item category wins over the list default` now actually passes a list default category, because
   with the old `categoryId = null` argument the guarantee it is named after could not fail.

No behaviour changed for the user: the app already posted through `postTripDrafts`, and still does. What
changed is that the item-titled variant no longer exists to drift out of step.

## Still true

- One ledger row per priced item; unpriced ticked items are skipped and never posted as a zero expense.
- Each posted item is stamped with `convertedTransactionId`, so a trip cannot post twice.
- Money formatting goes through `FormatMoneyUseCase`; decimal, never `String.format`/`Double`.
- Migration 132 is untouched by this change.
