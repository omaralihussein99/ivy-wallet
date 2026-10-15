package com.ivy.shopping

import java.util.Locale

/**
 * Formats a planned money amount for display.
 *
 * The shopping list deliberately uses plain numbers instead of the ledger's money formatter:
 * the estimate here is a planning aid, not a posted transaction, so it must never be confused
 * with a real balance.
 */
internal fun formatAmount(value: Double): String =
    String.format(Locale.getDefault(), "%.2f", value)

/**
 * Quantities read badly as "1.0", so whole quantities are shown without decimals.
 */
internal fun formatQuantity(value: Double): String = when {
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> String.format(Locale.getDefault(), "%.2f", value)
}
