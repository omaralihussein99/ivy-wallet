package com.ivy.shopping

import kotlin.math.round

/**
 * Quantity rendering.
 *
 * This is deliberately *not* how money is rendered. Every price, line total and running total on the
 * shopping screens goes through Ivy's `FormatMoneyUseCase` (see the view models), so it respects the
 * user's decimal and grouping settings. A quantity is a plain count of things - "2", "1.5" - with no
 * currency, no grouping and no thousands separator, and it reads badly as "2.0", so whole quantities
 * lose the decimal point.
 */
internal fun formatQuantity(value: Double): String {
    if (value % 1.0 == 0.0) return value.toInt().toString()

    val hundredths = round(value * 100.0).toLong()
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toInt()
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        fraction < 10 -> "$whole.0$fraction"
        else -> "$whole.$fraction"
    }
}
