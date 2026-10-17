package com.ivy.accounts.hierarchy

import java.util.UUID

/**
 * One row of the indented account tree. Every amount is already formatted by the view model using
 * Ivy's `FormatMoneyUseCase` - the UI never formats money itself.
 */
data class AccountHierarchyRowViewState(
    val id: UUID,
    val name: String,
    /** How deep the account sits, used to indent it. 0 for roots. */
    val depth: Int,
    /**
     * The account's own balance **in its own currency**, formatted, with the currency code. This is
     * what the account really holds; it is never a converted figure.
     */
    val ownBalanceText: String,
    /** The account's own currency code, shown next to its balance. */
    val currency: String,
    /**
     * Own balance plus every descendant, converted to the base currency, formatted and labelled.
     * Null when the account has no usable rate, so the UI marks it instead of showing a lie.
     */
    val rolledUpBalanceText: String?,
    /** The name of the parent, or null for a root account. */
    val parentName: String?,
    val hasChildren: Boolean,
    val expanded: Boolean,
    /**
     * True when the account's currency has no usable exchange rate. Its money is NOT in any
     * base-currency figure (it is not 0 and it is not converted at 1:1), so the row must say so.
     */
    val noRate: Boolean,
)

/** A choice in the parent picker. */
data class AccountParentOptionViewState(
    val id: UUID,
    val name: String,
)

/**
 * One currency's own-currency subtotal. [ownTotalText] is native and always honest;
 * [baseTotalText] is null exactly when the whole currency is excluded from the base total.
 */
data class CurrencySubtotalViewState(
    val currency: String,
    /** The money held in this currency, formatted in that currency. */
    val ownTotalText: String,
    /** The same money in the base currency, formatted and labelled, or null when there is no rate. */
    val baseTotalText: String?,
    val includedInBaseTotal: Boolean,
)

data class AccountHierarchyViewState(
    val rows: List<AccountHierarchyRowViewState>,
    /** The grand total, obeying the double-count rule (roots only). Formatted. */
    val totalText: String,
    /** The base currency every rolled-up and total figure is expressed in. */
    val baseCurrency: String,
    val currencySubtotals: List<CurrencySubtotalViewState>,
    /** How many accounts were excluded from the base-currency total because they have no rate. */
    val excludedCount: Int,
    val parentPickerVisible: Boolean,
    /** The account whose parent is being chosen, or null when the picker is closed. */
    val parentPickerAccountId: UUID?,
    val parentPickerAccountName: String,
    val parentPickerOptions: List<AccountParentOptionViewState>,
    /** True when the last pick was rejected by the cycle/self-parent validator. */
    val parentPickerRejected: Boolean,
)

sealed interface AccountHierarchyEvent {
    data object OnBack : AccountHierarchyEvent
    data class OnToggleExpand(val accountId: UUID) : AccountHierarchyEvent
    data class OnOpenParentPicker(val accountId: UUID) : AccountHierarchyEvent
    data object OnDismissParentPicker : AccountHierarchyEvent

    /** Passing a null [parentId] clears the parent ("no parent"). */
    data class OnSetParent(val accountId: UUID, val parentId: UUID?) : AccountHierarchyEvent
}
