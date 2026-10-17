package com.ivy.shopping

import com.ivy.data.db.entity.ShoppingItemEntity
import com.ivy.data.db.entity.ShoppingListEntity
import java.util.UUID

/**
 * Pure shopping list rules. No Android, no database - so they can be unit tested directly.
 *
 * The shape they encode: a trip is ticked in the shop, then posted to the ledger in one of two
 * ways, chosen by the user at checkout (see [ShoppingCheckoutMode]):
 *
 *  - one transaction for the whole trip, with every item of the trip pointing at it through
 *    `shopping_items.convertedTransactionId`, so the transaction screen can show the breakdown;
 *  - one expense row per item, which is what the feature shipped with.
 *
 * Both shapes keep each item's own price; the per-item shape additionally keeps each item's own
 * category on its own ledger row.
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

// ---------------------------------------------------------------- screen state

/**
 * The detail screen is opened with a list id, and the id is an argument, not a process-wide
 * setting: it has to load whatever list it was opened with, and load it again when the screen is
 * reused for another id. A view model that captured the id once in its constructor kept showing an
 * empty screen for the next list.
 */
internal fun shouldLoadList(loadedListId: UUID?, requestedListId: UUID?): Boolean =
    requestedListId != null && requestedListId != loadedListId

// ---------------------------------------------------------------- editing an item

/**
 * What the user typed into an item's price box, when saving it from the item editor.
 *
 * This is stricter than [parsePriceInput], which is about the checkout sheet and treats anything
 * unusable as "no price". Here a blank box means the user really wants to clear the price, while
 * garbage has to be refused instead of silently clearing a price the user has already set.
 */
internal sealed interface PriceEdit {
    data object Cleared : PriceEdit
    data class Priced(val value: Double) : PriceEdit
    data object Invalid : PriceEdit
}

internal fun parsePriceEdit(text: String): PriceEdit {
    val normalised = text.trim().replace(',', '.')
    if (normalised.isEmpty()) return PriceEdit.Cleared
    val value = normalised.toDoubleOrNull() ?: return PriceEdit.Invalid
    return if (value > 0.0 && value.isFinite()) PriceEdit.Priced(value) else PriceEdit.Invalid
}

/**
 * A quantity as the user typed it. Anything that is not a positive, finite number is refused: a
 * quantity of zero would make a line total meaningless and a negative one would flip its sign.
 */
internal fun parseQuantityInput(text: String): Double? {
    val normalised = text.trim().replace(',', '.')
    if (normalised.isEmpty()) return null
    val value = normalised.toDoubleOrNull() ?: return null
    return if (value > 0.0 && value.isFinite()) value else null
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
 * How a finished trip is posted. One transaction for the whole trip is the default because that is
 * what the owner asked for; the per-item rows stay available because they are the only shape that
 * keeps each item's own category on its own ledger row.
 */
enum class ShoppingCheckoutMode { SingleTransaction, RowPerItem }

/**
 * The category a whole-trip transaction is pre-filled with: the category most of the trip's items
 * already carry. Null when none of them has one, in which case the sheet offers "no category".
 */
internal fun mostCommonCategoryId(items: List<ShoppingItemEntity>): UUID? = items
    .mapNotNull { it.categoryId }
    .groupingBy { it }
    .eachCount()
    .maxByOrNull { it.value }
    ?.key

/**
 * The single ledger row a whole trip will become.
 *
 * The category is one category for the whole row - that is the price of posting one row instead of
 * many - and the per-item categories stay on the items and are shown in the breakdown. Null means
 * "do not post": no account to pay from, or nothing on the trip has a usable price. A 0.00
 * transaction is never posted.
 */
internal data class SingleCheckoutLine(
    val title: String,
    val amount: Double,
    val accountId: UUID,
    val categoryId: UUID?,
)

internal fun buildSingleTransactionLine(
    drafts: List<CheckoutDraft>,
    priceText: Map<UUID, String>,
    tripName: String,
    accountId: UUID?,
    categoryId: UUID?,
): SingleCheckoutLine? {
    val payingAccountId = accountId ?: return null
    val rows = resolvedCheckoutRows(drafts, priceText, fallbackAccountId = payingAccountId)
    if (rows.isEmpty()) return null
    val total = rows.sumOf { it.amount }
    if (total <= 0.0) return null
    return SingleCheckoutLine(
        title = tripName,
        amount = total,
        accountId = payingAccountId,
        categoryId = categoryId,
    )
}

/**
 * The items that are still owed a ledger row. Whatever already carries a transaction id was posted
 * and must never be posted again - that is the same guard per-item mode uses, applied before a
 * whole-trip post so a second tap can never pay for the same item twice.
 */
internal fun List<ShoppingItemEntity>.unpostedItems(): List<ShoppingItemEntity> =
    filter { it.convertedTransactionId == null }

/**
 * A trip is finished only when every one of its items is in the ledger. Archiving a trip while
 * something on it was never priced (and therefore never posted) would quietly throw that item away.
 */
internal fun shouldArchiveTrip(items: List<ShoppingItemEntity>): Boolean =
    items.isNotEmpty() && items.all { it.convertedTransactionId != null }

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
