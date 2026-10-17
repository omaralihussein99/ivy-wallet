package com.ivy.shopping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The price history is pure, so it is checked here on the JVM - no database, no device, no formatting.
 *
 * Every case the hint can meet is covered: no history, one occurrence, several, an exact date tie,
 * the name normalisation (case and surrounding whitespace) and the currency restriction.
 */
class ShoppingPriceHistoryTest {

    private val day1: Instant = Instant.parse("2026-01-10T10:00:00Z")
    private val day2: Instant = Instant.parse("2026-01-17T10:00:00Z")
    private val day3: Instant = Instant.parse("2026-01-24T10:00:00Z")

    private fun posted(
        id: String,
        name: String,
        price: Double,
        at: Instant = day1,
        currency: String = "USD",
    ) = PostedItemPrice(
        itemId = id,
        name = name,
        unitPrice = price,
        currency = currency,
        postedAt = at,
    )

    // ------------------------------------------------------------ no history / one / many

    @Test
    fun `an item that was never posted has no history`() {
        val result = priceHistory(
            entries = listOf(posted("1", "Bread", 2.0)),
            itemName = "Milk",
            currency = "USD",
        )

        assertNull("no history must be null, not a 0.00 hint", result)
    }

    @Test
    fun `an empty history has no history`() {
        assertNull(priceHistory(entries = emptyList(), itemName = "Milk", currency = "USD"))
    }

    @Test
    fun `one occurrence reports its price, count of one and its date`() {
        val result = priceHistory(
            entries = listOf(posted("1", "Milk", 4.2, at = day1)),
            itemName = "Milk",
            currency = "USD",
        )

        assertEquals(4.2, result!!.lastPrice, 0.0001)
        assertEquals(1, result.times)
        assertEquals(day1, result.lastDate)
        assertEquals("USD", result.currency)
    }

    @Test
    fun `several occurrences report the latest price, the count and the date it came from`() {
        val result = priceHistory(
            entries = listOf(
                posted("1", "Milk", 4.2, at = day1),
                posted("2", "Milk", 4.8, at = day2),
                posted("3", "Milk", 4.5, at = day3),
            ),
            itemName = "Milk",
            currency = "USD",
        )

        assertEquals(4.5, result!!.lastPrice, 0.0001) // the latest, not the most expensive (4.8)
        assertEquals(3, result.times)
        assertEquals(day3, result.lastDate)
    }

    @Test
    fun `an exact date tie is broken by the higher price, deterministically`() {
        val result = priceHistory(
            entries = listOf(
                posted("b", "Milk", 4.2, at = day2),
                posted("a", "Milk", 4.9, at = day2),
                posted("c", "Milk", 4.5, at = day1),
            ),
            itemName = "Milk",
            currency = "USD",
        )

        assertEquals(4.9, result!!.lastPrice, 0.0001)
        assertEquals(3, result.times)
        assertEquals(day2, result.lastDate)
    }

    @Test
    fun `the same instant and the same price still pick the same entry every time`() {
        val entries = listOf(
            posted("b", "Milk", 4.2, at = day2),
            posted("a", "Milk", 4.2, at = day2),
        )

        val first = priceHistory(entries, "Milk", "USD")
        val second = priceHistory(entries.reversed(), "Milk", "USD")

        assertEquals("input order must not change the hint", first, second)
        assertEquals(4.2, first!!.lastPrice, 0.0001)
        assertEquals(2, first.times)
    }

    // ------------------------------------------------------------ name normalisation

    @Test
    fun `names that differ only by case or surrounding whitespace match`() {
        val result = priceHistory(
            entries = listOf(
                posted("1", "milk", 4.2, at = day1),
                posted("2", "MILK", 4.4, at = day2),
                posted("3", "  Milk  ", 4.6, at = day3),
            ),
            itemName = "Milk",
            currency = "USD",
        )

        assertEquals(3, result!!.times)
        assertEquals(4.6, result.lastPrice, 0.0001)
    }

    @Test
    fun `runs of inner whitespace are collapsed before matching`() {
        val result = priceHistory(
            entries = listOf(posted("1", "whole   milk", 4.2)),
            itemName = "  whole \t milk ",
            currency = "USD",
        )

        assertEquals(1, result!!.times)
        assertEquals(4.2, result.lastPrice, 0.0001)
    }

    @Test
    fun `names that differ by more than case or whitespace do not match`() {
        assertNull(
            priceHistory(
                entries = listOf(posted("1", "Milk 2l", 4.2)),
                itemName = "Milk",
                currency = "USD",
            ),
        )
        // Not fuzzy: one extra letter is a different product.
        assertNull(
            priceHistory(
                entries = listOf(posted("1", "Milks", 4.2)),
                itemName = "Milk",
                currency = "USD",
            ),
        )
    }

    @Test
    fun `the normalisation is exactly trim plus collapsed whitespace plus lowercase`() {
        assertEquals("whole milk", normaliseItemName("  Whole \n\t MILK  "))
        assertEquals("", normaliseItemName("   "))
    }

    // ------------------------------------------------------------ currency restriction

    @Test
    fun `history is restricted to the same currency and never mixed`() {
        val entries = listOf(
            posted("1", "Milk", 4.2, at = day1, currency = "USD"),
            posted("2", "Milk", 99.0, at = day3, currency = "EUR"),
        )

        val usd = priceHistory(entries, "Milk", "USD")
        val eur = priceHistory(entries, "Milk", "EUR")

        assertEquals(1, usd!!.times)
        assertEquals(4.2, usd.lastPrice, 0.0001)
        assertEquals(1, eur!!.times)
        assertEquals(99.0, eur.lastPrice, 0.0001)
    }

    @Test
    fun `a currency with no matching history yields no hint instead of a converted one`() {
        val entries = listOf(posted("1", "Milk", 4.2, currency = "EUR"))

        assertNull(priceHistory(entries, "Milk", "USD"))
    }

    @Test
    fun `currency codes are compared case-insensitively and trimmed`() {
        val result = priceHistory(
            entries = listOf(posted("1", "Milk", 4.2, currency = " usd ")),
            itemName = "Milk",
            currency = "USD",
        )

        assertEquals(1, result!!.times)
        assertEquals("USD", result.currency)
    }

    // ------------------------------------------------------------ unusable rows

    @Test
    fun `zero, negative and non-finite prices are not purchases and are ignored`() {
        val result = priceHistory(
            entries = listOf(
                posted("1", "Milk", 0.0, at = day3),
                posted("2", "Milk", -4.0, at = day2),
                posted("3", "Milk", Double.NaN, at = day1),
                posted("4", "Milk", 4.2, at = day1),
            ),
            itemName = "Milk",
            currency = "USD",
        )

        assertEquals(1, result!!.times)
        assertEquals(4.2, result.lastPrice, 0.0001)
    }

    @Test
    fun `a blank item name or a blank currency has no history`() {
        val entries = listOf(posted("1", "Milk", 4.2))

        assertNull(priceHistory(entries, itemName = "   ", currency = "USD"))
        assertNull(priceHistory(entries, itemName = "Milk", currency = "  "))
    }
}
