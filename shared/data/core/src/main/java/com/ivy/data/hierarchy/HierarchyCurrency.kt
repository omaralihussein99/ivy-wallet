package com.ivy.data.hierarchy

import com.ivy.data.model.AccountId
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * The currency rules of the account hierarchy, expressed as pure functions.
 *
 * A hierarchy may mix currencies: the parent/child rules only reject self-parenting and cycles, so a
 * EUR account can be a child of a USD account. That is legitimate, but it means two different
 * questions must never be answered with one number:
 *
 *  1. **How much do I hold in each currency?** That is [currencySubtotals], and it needs no exchange
 *     rate at all: each account's OWN balance stays in its own currency (own balances are disjoint,
 *     so a per-currency subtotal never double counts a child).
 *  2. **What is the whole tree worth in the base currency?** That is [baseGrandTotal], and it needs a
 *     usable rate for every non-base currency. It keeps the double-count rule intact: the total is
 *     still the sum of the ROOT accounts' rolled-up balances, so a child is counted once, inside its
 *     parent, and a parent is never counted twice.
 *
 * ### The missing/stale-rate rule
 *
 * An account whose currency cannot be valued in the base currency is **excluded** from every
 * base-currency figure and reported by [accountsExcludedFromBaseTotal] so the UI can mark its row.
 * It is never counted as 0 (that would silently lose money) and never converted at 1:1 (that would
 * silently invent money).
 *
 * What "stale" means here is deliberately stated against the model that actually exists:
 * `exchange_rates` stores `(baseCurrency, currency, rate, manualOverride)` and **no timestamp**. The
 * model therefore cannot express the age of a rate, so a rate cannot be aged or expired from it. The
 * only trust signals it has are:
 *
 *  - the row exists for the pair (or for the reciprocal pair), and
 *  - the value survives the same validity rule the rest of the app uses for a rate
 *    (`PositiveDouble` / `validateRate`: finite and strictly greater than zero).
 *
 * So [RateTable.rateToBase] returns null - "missing or stale", the same outcome - when the pair is
 * absent, when the value is zero, negative, NaN or infinite, or when the row belongs to an unrelated
 * pair. `manualOverride` rows are trusted like any other row: a user-entered rate is a deliberate
 * value, and the model offers no timestamp with which to call it old.
 *
 * If a real age ever becomes available, the honest place to add it is this file: give [RateRow] a
 * timestamp and a horizon. That would need a schema change (`migration 133`), which this batch
 * deliberately does **not** make - 132 stays frozen. Recorded in
 * `PHASE2-ACCOUNT-HIERARCHY-HANDOVER.md`.
 */

/**
 * One stored exchange rate from the app's own model: 1 unit of [currency] is worth [rate] units of
 * [baseCurrency].
 */
data class RateRow(
    val baseCurrency: String,
    val currency: String,
    val rate: Double,
)

/** How an account's own balance reached the base currency - or why it did not. */
enum class BaseConversion {
    /** The account already holds the base currency; no rate is needed. */
    BaseCurrency,

    /** Converted with a stored rate, direct or reciprocal. */
    Converted,

    /**
     * No usable rate: the balance is excluded from every base-currency figure. This covers both a
     * missing row and a row this model cannot trust ("stale" - see the file KDoc).
     */
    MissingRate,
}

/** One account, its own balance, and how (or whether) it can be valued in the base currency. */
data class ValorisedAccount(
    val id: AccountId,
    /** The account's own currency, normalised to upper case. Empty when the data has none. */
    val currency: String,
    /** The account's own balance, in its own currency. */
    val ownBalance: BigDecimal,
    /** The own balance in the base currency, or null when there is no usable rate. */
    val ownBalanceInBase: BigDecimal?,
    val conversion: BaseConversion,
)

/**
 * What the user holds in one currency. [ownTotal] is native, so it is honest even without a rate;
 * [ownTotalInBase] is null exactly when the whole currency is excluded from the base-currency total.
 */
data class CurrencySubtotal(
    val currency: String,
    val ownTotal: BigDecimal,
    val ownTotalInBase: BigDecimal?,
    val includedInBaseTotal: Boolean,
)

/** True when a stored rate value can be used as a price. The same rule `validateRate` applies. */
fun isUsableRate(rate: Double): Boolean = rate.isFinite() && rate > 0.0

/**
 * The rates the screen loaded, plus the base currency they are relative to.
 *
 * [rows] may be empty: every non-base currency is then reported as missing, instead of being guessed
 * at. The base currency itself never needs a row.
 */
data class RateTable(
    val baseCurrency: String,
    val rows: List<RateRow> = emptyList(),
) {
    /** The normalised base currency code. */
    val baseCode: String get() = normaliseCurrency(baseCurrency)

    /**
     * How many base-currency units one unit of [currency] is worth, or null when the pair is missing
     * or the stored value is not usable. Identity for the base currency itself.
     */
    fun rateToBase(currency: String): BigDecimal? {
        val code = normaliseCurrency(currency)
        if (code.isEmpty()) return null
        if (code == baseCode) return BigDecimal.ONE

        val direct = rows.firstOrNull { row ->
            normaliseCurrency(row.baseCurrency) == baseCode &&
                normaliseCurrency(row.currency) == code &&
                isUsableRate(row.rate)
        }
        if (direct != null) return BigDecimal.valueOf(direct.rate)

        // The app stores rates relative to one base currency, so the reciprocal pair is the only
        // other stored shape that can answer this question. 1 EUR = 1.25 USD <=> 1 USD = 0.8 EUR.
        val inverse = rows.firstOrNull { row ->
            normaliseCurrency(row.baseCurrency) == code &&
                normaliseCurrency(row.currency) == baseCode &&
                isUsableRate(row.rate)
        }
        if (inverse != null) {
            return BigDecimal.ONE.divide(
                BigDecimal.valueOf(inverse.rate),
                CONVERSION_PRECISION,
            )
        }
        return null
    }
}

private val CONVERSION_PRECISION = MathContext(12, RoundingMode.HALF_UP)

private fun normaliseCurrency(currency: String): String =
    currency.trim().uppercase(Locale.ROOT)

/**
 * Every account's own balance together with its base-currency value and the reason it has one (or
 * not). This is the single place the missing/stale-rate decision is made.
 */
fun valorise(nodes: List<HierarchyNode>, rates: RateTable): Map<AccountId, ValorisedAccount> =
    nodes.associate { node ->
        val code = normaliseCurrency(node.currency)
        val rate = if (code.isEmpty()) null else rates.rateToBase(code)
        val conversion = when {
            code.isEmpty() -> BaseConversion.MissingRate
            code == rates.baseCode -> BaseConversion.BaseCurrency
            rate != null -> BaseConversion.Converted
            else -> BaseConversion.MissingRate
        }
        node.id to ValorisedAccount(
            id = node.id,
            currency = code,
            ownBalance = node.ownBalance,
            ownBalanceInBase = if (rate == null || conversion == BaseConversion.MissingRate) {
                null
            } else {
                node.ownBalance.multiply(rate)
            },
            conversion = conversion,
        )
    }

/**
 * The money held per currency, from each account's OWN balance.
 *
 * Own balances are disjoint, so summing them (roots and children alike) is not the naive sum that
 * breaks the double-count rule: it never adds a parent's rolled-up figure on top of its child. It
 * answers "how much do I have in EUR", not "what is the tree worth".
 *
 * A currency whose rate is unusable still gets its native subtotal - that number is real - but
 * [CurrencySubtotal.ownTotalInBase] is null and [CurrencySubtotal.includedInBaseTotal] is false, so
 * the whole currency is excluded from the base-currency total and can be marked in the UI.
 *
 * The base currency is sorted first, then the other codes alphabetically.
 */
fun currencySubtotals(nodes: List<HierarchyNode>, rates: RateTable): List<CurrencySubtotal> {
    val nativeTotals = LinkedHashMap<String, BigDecimal>()
    nodes.forEach { node ->
        val code = normaliseCurrency(node.currency)
        // An account without a currency cannot be attributed to a currency honestly.
        if (code.isEmpty()) return@forEach
        nativeTotals[code] = (nativeTotals[code] ?: BigDecimal.ZERO).add(node.ownBalance)
    }

    return nativeTotals.map { (code, native) ->
        val rate = rates.rateToBase(code)
        if (rate == null) {
            CurrencySubtotal(
                currency = code,
                ownTotal = native,
                ownTotalInBase = null,
                includedInBaseTotal = false,
            )
        } else {
            CurrencySubtotal(
                currency = code,
                ownTotal = native,
                ownTotalInBase = native.multiply(rate),
                includedInBaseTotal = true,
            )
        }
    }.sortedWith(compareBy<CurrencySubtotal> { it.currency != rates.baseCode }.thenBy { it.currency })
}

/**
 * The rolled-up balance of every account, in the base currency, excluding every account whose
 * currency has no usable rate.
 *
 * The roll-up walks the same effective parents as the currency-agnostic rules (orphans and malformed
 * cycles included), and it walks *through* an excluded account: if a EUR parent has a USD child, the
 * child's USD balance is still counted even though the parent's EUR balance cannot be.
 *
 * An excluded account contributes nothing - not its balance and not a 0 placeholder.
 */
fun baseRolledUpBalances(nodes: List<HierarchyNode>, rates: RateTable): Map<AccountId, BigDecimal> {
    if (nodes.isEmpty()) return emptyMap()
    val valued = valorise(nodes, rates)
    val parents = effectiveParents(nodes)
    val children = HashMap<AccountId, MutableList<HierarchyNode>>()
    nodes.forEach { node ->
        val parentId = parents[node.id] ?: return@forEach
        children.getOrPut(parentId) { mutableListOf() }.add(node)
    }
    val byId = nodes.associateBy { it.id }
    val memo = HashMap<AccountId, BigDecimal>(nodes.size)

    fun rollUp(id: AccountId): BigDecimal {
        memo[id]?.let { return it }
        val node = byId[id] ?: return BigDecimal.ZERO
        var sum = valued[id]?.ownBalanceInBase ?: BigDecimal.ZERO
        children[id]?.forEach { child ->
            sum = sum.add(rollUp(child.id))
        }
        memo[id] = sum
        return sum
    }

    return nodes.associate { it.id to rollUp(it.id) }
}

/**
 * The user's base-currency total, obeying the double-count rule: the rolled-up balances of the ROOT
 * accounts only. Children are already inside their parent, and excluded accounts are not in it at
 * all - not even as zero.
 */
fun baseGrandTotal(nodes: List<HierarchyNode>, rates: RateTable): BigDecimal {
    if (nodes.isEmpty()) return BigDecimal.ZERO
    val rolled = baseRolledUpBalances(nodes, rates)
    return rootIds(nodes).fold(BigDecimal.ZERO) { total, id ->
        total.add(rolled[id] ?: BigDecimal.ZERO)
    }
}

/** The accounts the UI must mark as "no rate": their money is not in any base-currency figure. */
fun accountsExcludedFromBaseTotal(nodes: List<HierarchyNode>, rates: RateTable): Set<AccountId> =
    valorise(nodes, rates).values
        .filter { it.conversion == BaseConversion.MissingRate }
        .mapTo(LinkedHashSet()) { it.id }
