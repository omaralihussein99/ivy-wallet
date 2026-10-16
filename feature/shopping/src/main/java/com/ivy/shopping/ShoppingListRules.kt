package com.ivy.shopping

import com.ivy.data.db.entity.ShoppingItemEntity
import com.ivy.data.db.entity.ShoppingListEntity
import java.util.UUID

/**
 * Pure shopping list rules. No Android, no database - so they can be unit tested directly.
 *
 * The shape they encode: a trip is ticked in the shop, then every ticked item is posted to the
 * ledger as its own expense line. Ivy has no multi-line transaction (no split support anywhere in
 * the code base), so one row per item is the only representation that keeps each item's own price
 * and its own category.
 */

/**
 * An item can be posted when it is ticked and has not been posted before. The second half is the
 * double-post guard: once an item carries a transaction id it is finished, forever.
 */
internal fun ShoppingItemEntity.isPostable(): Boolean =
    isChecked && convertedTransactionId == null

/** Line total for one item. A missing price counts as zero, never as a guess. */
internal fun ShoppingItemEntity.lineTotal(): Double = (unitPrice ?: 0.0) * quantity

/** What the trip will still cost for the items not yet in the basket. */
internal fun List<ShoppingItemEntity>.remainingEstimate(): Double =
    filterNot { it.isChecked }.sumOf { it.lineTotal() }

/** How many items are ticked, out of how many. */
internal fun List<ShoppingItemEntity>.checkedCount(): Int = count { it.isChecked }

/** The running total of everything ticked and postable, used on the checkout sheet. */
internal fun List<ShoppingItemEntity>.checkoutTotal(): Double =
    filter { it.isPostable() }.sumOf { it.lineTotal() }

/** Items that are ticked but still have no usable price, so checkout has to ask for one. */
internal fun List<ShoppingItemEntity>.awaitingPrice(): List<ShoppingItemEntity> =
    filter { it.isPostable() && (it.unitPrice ?: 0.0) <= 0.0 }

/**
 * One expense line to write to the ledger.
 *
 * There is exactly one shape: [title] is the trip's name and [description] is the name of the item
 * the row came from, so the money is filed under the trip while the row still says what was bought.
 */
internal data class CheckoutLine(
    val itemId: UUID,
    val title: String,
    val amount: Double,
    val accountId: UUID,
    val categoryId: UUID?,
    val description: String? = null,
)

/**
 * The stored unit price to persist for an item after its line has been posted, so next time the trip
 * is repeated the price is remembered instead of asked for again.
 *
 * Returns null when the quantity is not usable, in which case the line amount is kept as the unit
 * price rather than dividing by zero.
 */
internal fun unitPriceFromLine(amount: Double, quantity: Double): Double =
    if (quantity > 0.0) amount / quantity else amount

// ---------------------------------------------------------------- templates and trips

/**
 * A template is the reusable original, a trip is the working copy. Only `true` is a template: rows
 * that predate templates carry NULL and those are trips.
 */
internal fun ShoppingListEntity.isTemplateList(): Boolean = isTemplate == true

/**
 * The trip that "new trip from template" creates.
 *
 * It is a copy of the template's metadata with a new identity, and it is explicitly *not* a template,
 * so the original can never be ticked or posted by accident.
 */
internal fun ShoppingListEntity.toNewTrip(id: UUID, orderNum: Double): ShoppingListEntity = copy(
    id = id,
    isTemplate = false,
    isArchived = false,
    orderNum = orderNum,
)

/**
 * The items a brand new trip starts with: the template's items, copied with fresh identities and all
 * shopping state cleared, because a template is never ticked and never posted.
 *
 * The template rows themselves are left untouched - only a new list of items is returned.
 */
internal fun copiedItemsForTrip(
    templateItems: List<ShoppingItemEntity>,
    tripId: UUID,
): List<ShoppingItemEntity> = templateItems.mapIndexed { index, item ->
    item.copy(
        id = UUID.randomUUID(),
        listId = tripId,
        isChecked = false,
        checkedAt = null,
        convertedTransactionId = null,
        orderNum = index.toDouble(),
    )
}

// ---------------------------------------------------------------- checkout sheet

/** Everything ticked and not yet posted, which is exactly what the checkout sheet reviews. */
internal fun List<ShoppingItemEntity>.postableItems(): List<ShoppingItemEntity> =
    filter { it.isPostable() }

/**
 * One box on the checkout sheet. Pre-filled from what is already known: the price and the category
 * come from the item, the account comes from the trip that is being checked out.
 */
internal data class CheckoutDraft(
    val itemId: UUID,
    val name: String,
    val quantity: Double,
    val unitPrice: Double?,
    val accountId: UUID?,
    val categoryId: UUID?,
)

/**
 * The paying account a trip starts with: the trip's own account, and when the trip has never been
 * given one the single account of the user if there is exactly one. That is not a guess - with one
 * account there is no choice to make - but with several accounts the sheet asks instead.
 */
internal fun defaultCheckoutAccountId(listAccountId: UUID?, accountIds: List<UUID>): UUID? =
    listAccountId ?: accountIds.singleOrNull()

/**
 * One draft per ticked item, in the order the items came in, so the sheet and the shop lists agree.
 */
internal fun checkoutDrafts(
    items: List<ShoppingItemEntity>,
    defaultAccountId: UUID?,
): List<CheckoutDraft> = items.postableItems().map { item ->
    CheckoutDraft(
        itemId = item.id,
        name = item.name,
        quantity = item.quantity,
        unitPrice = item.unitPrice,
        accountId = defaultAccountId,
        categoryId = item.categoryId,
    )
}

/**
 * What the user typed into a price box. Blank, malformed, non-finite or non-positive input is null,
 * which means "no price" and therefore "do not post a line".
 *
 * A comma is accepted as a decimal separator because that is what half the keyboards produce.
 */
internal fun parsePriceInput(text: String): Double? {
    val normalised = text.trim().replace(',', '.')
    if (normalised.isEmpty()) return null
    val value = normalised.toDoubleOrNull() ?: return null
    return if (value > 0.0 && value.isFinite()) value else null
}

/** The text a price box is pre-filled with. No price means an empty box, never a fake zero. */
internal fun priceInputText(unitPrice: Double?): String =
    if (unitPrice == null || unitPrice <= 0.0) "" else formatQuantity(unitPrice)

/** A draft with its price entered, i.e. a row that will really become a ledger line. */
internal data class ResolvedCheckoutRow(
    val draft: CheckoutDraft,
    val price: Double,
) {
    val amount: Double get() = price * draft.quantity
}

/**
 * The rows that will actually post: a usable price and a paying account. Everything else is skipped
 * rather than guessed at, which is why this is also the only honest source for the running total and
 * for whether the Confirm button may be enabled.
 */
internal fun resolvedCheckoutRows(
    drafts: List<CheckoutDraft>,
    priceText: Map<UUID, String>,
    fallbackAccountId: UUID? = null,
): List<ResolvedCheckoutRow> = drafts.mapNotNull { draft ->
    val price = parsePriceInput(priceText[draft.itemId].orEmpty()) ?: return@mapNotNull null
    if ((draft.accountId ?: fallbackAccountId) == null) return@mapNotNull null
    ResolvedCheckoutRow(draft = draft, price = price)
}

/** The running total of the checkout sheet, before anything is written. */
internal fun checkoutTotal(
    drafts: List<CheckoutDraft>,
    priceText: Map<UUID, String>,
    fallbackAccountId: UUID? = null,
): Double = resolvedCheckoutRows(drafts, priceText, fallbackAccountId).sumOf { it.amount }

/**
 * The ledger lines the sheet will write: one expense per resolved row, titled with the trip's name
 * (the item it came from is kept in the description), paid from the account the row shows and filed
 * under the category the row shows.
 *
 * One row per item is the only shape Ivy can represent - there is no multi-line transaction anywhere
 * in the code base - and it is the shape that keeps each item's own price and category.
 */
internal fun buildDraftLines(
    drafts: List<CheckoutDraft>,
    priceText: Map<UUID, String>,
    tripName: String,
    fallbackAccountId: UUID? = null,
    fallbackCategoryId: UUID? = null,
): List<CheckoutLine> = resolvedCheckoutRows(drafts, priceText, fallbackAccountId)
    .mapNotNull { row ->
        val accountId = row.draft.accountId ?: fallbackAccountId ?: return@mapNotNull null
        CheckoutLine(
            itemId = row.draft.itemId,
            title = tripName,
            description = row.draft.name,
            amount = row.amount,
            accountId = accountId,
            categoryId = row.draft.categoryId ?: fallbackCategoryId,
        )
    }
