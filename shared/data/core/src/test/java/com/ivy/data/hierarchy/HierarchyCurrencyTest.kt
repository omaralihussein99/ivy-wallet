package com.ivy.data.hierarchy

import com.ivy.data.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The currency rules of the hierarchy are pure, so they are checked here on the JVM.
 *
 * The two things these tests exist to protect:
 *  - a mixed-currency tree must never be valued with a missing rate as 0 or at 1:1;
 *  - the base-currency total must stay the roots-only roll-up, so neither the naive sum nor the
 *    double count can come back.
 */
class HierarchyCurrencyTest {

    private fun id(n: Long): AccountId = AccountId(UUID(0L, n))

    private fun money(value: String): BigDecimal = BigDecimal(value)

    private fun assertMoney(expected: String, actual: BigDecimal?) {
        assertEquals("expected $expected but was $actual", 0, money(expected).compareTo(actual))
    }

    private fun node(
        accountId: AccountId,
        parent: AccountId? = null,
        own: String = "0",
        currency: String = "USD",
        orderNum: Double = 0.0,
    ) = HierarchyNode(
        id = accountId,
        parentId = parent,
        ownBalance = money(own),
        orderNum = orderNum,
        currency = currency,
    )

    private fun rates(base: String = "USD", vararg rows: RateRow) =
        RateTable(baseCurrency = base, rows = rows.toList())

    private fun row(base: String, currency: String, rate: Double) =
        RateRow(baseCurrency = base, currency = currency, rate = rate)

    // ------------------------------------------------------------ per-currency subtotals

    @Test
    fun `per-currency subtotals group the accounts by their own currency`() {
        val usdParent = id(1)
        val usdChild = id(2)
        val eur = id(3)
        val nodes = listOf(
            node(usdParent, own = "100", currency = "USD"),
            node(usdChild, parent = usdParent, own = "30", currency = "USD"),
            node(eur, own = "50", currency = "EUR"),
        )

        val subtotals = currencySubtotals(nodes, rates("USD", row("USD", "EUR", 2.0)))

        // Own balances are disjoint: USD 100 + 30 = 130, and it is NOT the parent's rolled-up 130
        // added to the child's 30. EUR keeps its own native 50.
        assertEquals(listOf("USD", "EUR"), subtotals.map { it.currency })
        assertMoney("130", subtotals.first { it.currency == "USD" }.ownTotal)
        assertMoney("50", subtotals.first { it.currency == "EUR" }.ownTotal)
    }

    @Test
    fun `the base currency is listed first and the rest alphabetically`() {
        val nodes = listOf(
            node(id(1), own = "1", currency = "EUR"),
            node(id(2), own = "2", currency = "USD"),
            node(id(3), own = "3", currency = "GBP"),
            node(id(4), own = "4", currency = "AED"),
        )

        val subtotals = currencySubtotals(
            nodes,
            rates("USD", row("USD", "EUR", 1.0), row("USD", "GBP", 1.0), row("USD", "AED", 1.0)),
        )

        assertEquals(listOf("USD", "AED", "EUR", "GBP"), subtotals.map { it.currency })
    }

    @Test
    fun `a currency with a usable rate gets a base-currency subtotal`() {
        val nodes = listOf(node(id(1), own = "40", currency = "EUR"))

        val subtotal = currencySubtotals(nodes, rates("USD", row("USD", "EUR", 1.25))).single()

        assertMoney("40", subtotal.ownTotal)
        assertMoney("50", subtotal.ownTotalInBase)
        assertTrue(subtotal.includedInBaseTotal)
    }

    // ------------------------------------------------------------ the double-count rule

    @Test
    fun `the base-currency total is the roots-only roll-up and not the naive sum`() {
        val parent = id(1)
        val child = id(2)
        val nodes = listOf(
            node(parent, own = "100", currency = "USD"),
            node(child, parent = parent, own = "30", currency = "USD"),
        )

        val total = baseGrandTotal(nodes, rates())

        // The child's 30 is inside the parent's rolled-up 130.
        assertMoney("130", total)

        // The naive alternative - adding every account's own balance after its roll-up - is 160.
        // This assertion is the guard: if anyone makes the total a naive sum, it fails.
        val rolled = baseRolledUpBalances(nodes, rates())
        val naiveSumOfEveryRolledUpBalance = nodes.fold(BigDecimal.ZERO) { acc, n ->
            acc.add(rolled.getValue(n.id))
        }
        assertMoney("160", naiveSumOfEveryRolledUpBalance)
        assertNotEquals(
            "the base total must never double count a child",
            naiveSumOfEveryRolledUpBalance,
            total,
        )

        // And the invariant the rule protects: every account's money counted exactly once.
        val sumOfOwnBalances = nodes.fold(BigDecimal.ZERO) { acc, n -> acc.add(n.ownBalance) }
        assertMoney("130", sumOfOwnBalances)
    }

    @Test
    fun `a deeper tree still counts every account exactly once in the base currency`() {
        val a = id(1)
        val b = id(2)
        val c = id(3)
        val d = id(4)
        val nodes = listOf(
            node(a, own = "10", currency = "USD"),
            node(b, parent = a, own = "20", currency = "USD"),
            node(c, parent = b, own = "30", currency = "USD"),
            node(d, parent = c, own = "40", currency = "USD"),
        )

        assertMoney("100", baseGrandTotal(nodes, rates()))
        assertMoney("100", baseRolledUpBalances(nodes, rates()).getValue(a))
        assertMoney("90", baseRolledUpBalances(nodes, rates()).getValue(b)) // 20 + 30 + 40
    }

    // ------------------------------------------------------------ missing / stale rates

    @Test
    fun `an account with no usable rate is excluded and reported, never counted as zero or at 1 to 1`() {
        val parent = id(1)
        val eurChild = id(2)
        val nodes = listOf(
            node(parent, own = "100", currency = "USD"),
            node(eurChild, parent = parent, own = "50", currency = "EUR"),
        )

        val total = baseGrandTotal(nodes, rates()) // no EUR row at all

        // Not 150 (a silent 1:1 conversion) and not "(100 + 0)" pretending the EUR money does not
        // exist: the EUR account is excluded and reported.
        assertMoney("100", total)
        assertNotEquals("a missing rate must not be treated as 1:1", money("150"), total)
        assertEquals(setOf(eurChild), accountsExcludedFromBaseTotal(nodes, rates()))

        // The row keeps its native value (it did not vanish) and the whole EUR subtotal is excluded
        // from the base-currency total, with the reason visible in the data.
        val eurSubtotal = currencySubtotals(nodes, rates()).first { it.currency == "EUR" }
        assertMoney("50", eurSubtotal.ownTotal)
        assertNull(eurSubtotal.ownTotalInBase)
        assertFalse(eurSubtotal.includedInBaseTotal)

        // The EUR child contributes nothing to the parent's base roll-up either.
        assertMoney("100", baseRolledUpBalances(nodes, rates()).getValue(parent))
    }

    @Test
    fun `a zero, negative, NaN or infinite rate is stale and unusable`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertFalse("$bad must not be usable", isUsableRate(bad))
            assertNull(
                "$bad must not convert",
                rates("USD", row("USD", "EUR", bad)).rateToBase("EUR"),
            )
        }
        assertTrue(isUsableRate(0.5))
    }

    @Test
    fun `a stale rate yields the same exclusion as a missing one`() {
        val nodes = listOf(
            node(id(1), own = "100", currency = "USD"),
            node(id(2), own = "50", currency = "EUR"),
        )
        val stale = rates("USD", row("USD", "EUR", 0.0))

        assertMoney("100", baseGrandTotal(nodes, stale))
        assertEquals(setOf(id(2)), accountsExcludedFromBaseTotal(nodes, stale))
    }

    @Test
    fun `a rate row for an unrelated pair does not answer the question`() {
        val table = rates("USD", row("GBP", "EUR", 1.1))

        assertNull(table.rateToBase("EUR"))
    }

    @Test
    fun `an excluded parent does not hide a usable child`() {
        val eurParent = id(1)
        val usdChild = id(2)
        val nodes = listOf(
            node(eurParent, own = "50", currency = "EUR"),
            node(usdChild, parent = eurParent, own = "20", currency = "USD"),
        )

        val total = baseGrandTotal(nodes, rates())

        // The EUR parent is excluded; the USD child is still real money and is counted.
        assertMoney("20", total)
        assertEquals(setOf(eurParent), accountsExcludedFromBaseTotal(nodes, rates()))
        assertMoney("20", baseRolledUpBalances(nodes, rates()).getValue(eurParent))
    }

    // ------------------------------------------------------------ usable conversions

    @Test
    fun `a direct rate converts a child into the base total`() {
        val parent = id(1)
        val eurChild = id(2)
        val nodes = listOf(
            node(parent, own = "100", currency = "USD"),
            node(eurChild, parent = parent, own = "50", currency = "EUR"),
        )

        val total = baseGrandTotal(nodes, rates("USD", row("USD", "EUR", 2.0)))

        assertMoney("200", total) // 100 + 50 * 2
        assertEquals(
            emptySet<AccountId>(),
            accountsExcludedFromBaseTotal(nodes, rates("USD", row("USD", "EUR", 2.0))),
        )
        // The EUR subtotal is native 50, worth 100 in the base currency.
        assertMoney(
            "100",
            currencySubtotals(nodes, rates("USD", row("USD", "EUR", 2.0)))
                .first { it.currency == "EUR" }
                .ownTotalInBase,
        )
    }

    @Test
    fun `a reciprocal rate is usable`() {
        // The app may store the pair either way round: 1 USD = 0.8 EUR means 1 EUR = 1.25 USD.
        val table = rates("USD", row("EUR", "USD", 0.8))

        assertMoney("1.25", table.rateToBase("EUR"))
        // The base currency is always the identity, whatever the stored rows say.
        assertMoney("1", table.rateToBase("USD"))
    }

    @Test
    fun `the base currency is the identity and needs no rate row`() {
        val table = rates("USD")

        assertMoney("1", table.rateToBase("USD"))
        assertMoney("1", table.rateToBase(" usd "))
        assertNull(table.rateToBase(""))
    }

    @Test
    fun `currency codes are matched case-insensitively and trimmed`() {
        val table = rates("usd", row("Usd", " eur ", 1.5))

        assertMoney("1.5", table.rateToBase("EUR"))
        assertMoney("1.5", table.rateToBase(" eur "))
    }

    @Test
    fun `an account without a currency is excluded instead of being added to the base total`() {
        val priced = id(1)
        val unknown = id(2)
        val nodes = listOf(
            node(priced, own = "10", currency = "USD"),
            node(unknown, own = "99", currency = ""),
        )

        assertMoney("10", baseGrandTotal(nodes, rates()))
        assertEquals(setOf(unknown), accountsExcludedFromBaseTotal(nodes, rates()))
    }
}
