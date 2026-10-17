package com.ivy.shopping

import java.time.Instant
import java.util.Locale

/**
 * What the user paid for an item before, as a hint in the checkout row.
 *
 * This is a query over data the app already stores, not a new table: every item a trip posted carries
 * `shopping_items.convertedTransactionId`, its name and the unit price that was really paid. The
 * aggregation is pure and lives here so it can be unit tested on the JVM (no Android, no Room, no
 * formatting).
 *
 * ### Name matching
 *
 * The same thing is typed differently from week to week ("Milk", " milk ", "MILK", "whole  milk"), so
 * the name is normalised before it is compared: [normaliseItemName] trims, collapses every run of
 * whitespace to one space and lowercases with [Locale.ROOT]. The comparison is therefore
 * case-insensitive *and* whitespace-insensitive, but it is not fuzzy - "milk" and "milk 2l" stay
 * different products on purpose. The chosen normalisation is tested in
 * `ShoppingPriceHistoryTest`.
 *
 * ### Currency discipline
 *
 * A price is only comparable in the same currency. The history is therefore **restricted** to entries
 * posted to accounts of the same currency as the row being priced, and the currency code is also
 * shown in the hint. Mixing currencies here would be a silent lie, and converting each historical
 * price would compare today's money with yesterday's exchange rate - so neither is done.
 */
data class PostedItemPrice(
    /** Stable id of the posted item, used only to break a tie deterministically. */
    val itemId: String,
    val name: String,
    /** The unit price that was really paid (never a line total). */
    val unitPrice: Double,
    /** The currency of the account the item was posted to. */
    val currency: String,
    /** When it was bought: `checkedAt`, falling back to the item's creation time. */
    val postedAt: Instant,
)

/** The hint itself. [lastDate] is the date the last price came from. */
data class PriceHistory(
    val lastPrice: Double,
    val times: Int,
    val lastDate: Instant,
    val currency: String,
)

/**
 * The normalisation the history is matched with: trim, collapse whitespace, lowercase.
 *
 * Deliberately not diacritic-insensitive and deliberately not fuzzy. Two different products that
 * differ by one letter stay different; that is safer than claiming a price belongs to something the
 * user did not buy.
 */
fun normaliseItemName(name: String): String =
    name.trim().replace(WHITESPACE_RUN, " ").lowercase(Locale.ROOT)

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * The last price paid for [itemName] in [currency], how many times, and when the last one was.
 *
 * Returns null when there is no history at all - the caller then shows nothing, rather than
 * "last paid 0.00".
 *
 * Rules:
 *  - only usable purchases count: a positive, finite unit price and a non-blank currency;
 *  - only entries in [currency] count (see the file KDoc);
 *  - the latest [PostedItemPrice.postedAt] wins. On an exact tie the higher price wins (the hint never
 *    understates what was paid), and [PostedItemPrice.itemId] is the final, stable tie-breaker.
 */
fun priceHistory(
    entries: List<PostedItemPrice>,
    itemName: String,
    currency: String,
): PriceHistory? {
    val key = normaliseItemName(itemName)
    val wantedCurrency = normaliseCurrency(currency)
    if (key.isEmpty() || wantedCurrency.isEmpty()) return null

    val matches = entries.filter { entry ->
        entry.unitPrice.isFinite() &&
            entry.unitPrice > 0.0 &&
            normaliseCurrency(entry.currency) == wantedCurrency &&
            normaliseItemName(entry.name) == key
    }
    if (matches.isEmpty()) return null

    val last = matches.maxWithOrNull(
        compareBy<PostedItemPrice> { it.postedAt }
            .thenBy { it.unitPrice }
            .thenBy { it.itemId }
    ) ?: return null

    return PriceHistory(
        lastPrice = last.unitPrice,
        times = matches.size,
        lastDate = last.postedAt,
        currency = wantedCurrency,
    )
}

private fun normaliseCurrency(currency: String): String =
    currency.trim().uppercase(Locale.ROOT)
