package com.ivy.shopping

import com.ivy.data.db.entity.ShoppingItemEntity
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
 */
internal data class CheckoutLine(
    val itemId: UUID,
    val title: String,
    val amount: Double,
    val accountId: UUID,
    val categoryId: UUID?,
)

/**
 * Turns the ticked items plus whatever prices the user entered at the till into the exact ledger
 * lines to write.
 *
 * An item with no price at all is skipped rather than posted as zero, because a zero-amount expense
 * is a lie about the ledger. A price entered at the till wins over the price stored on the item,
 * since the till is where the real number comes from.
 */
internal fun buildCheckoutLines(
    items: List<ShoppingItemEntity>,
    prices: Map<UUID, Double>,
    accountId: UUID,
    categoryId: UUID?,
): List<CheckoutLine> = items
    .filter { it.isPostable() }
    .mapNotNull { item ->
        val price = prices[item.id] ?: item.unitPrice ?: return@mapNotNull null
        if (price <= 0.0) return@mapNotNull null
        CheckoutLine(
            itemId = item.id,
            title = item.name,
            amount = price * item.quantity,
            accountId = accountId,
            categoryId = item.categoryId ?: categoryId,
        )
    }

/**
 * The stored unit price to persist for an item after its line has been posted, so next time the trip
 * is repeated the price is remembered instead of asked for again.
 *
 * Returns null when the quantity is not usable, in which case the line amount is kept as the unit
 * price rather than dividing by zero.
 */
internal fun unitPriceFromLine(amount: Double, quantity: Double): Double =
    if (quantity > 0.0) amount / quantity else amount
