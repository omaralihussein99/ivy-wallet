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
    /** The account's own balance, formatted. */
    val ownBalanceText: String,
    /** Own balance plus every descendant, formatted. Distinct from the own balance. */
    val rolledUpBalanceText: String,
    /** The name of the parent, or null for a root account. */
    val parentName: String?,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

/** A choice in the parent picker. */
data class AccountParentOptionViewState(
    val id: UUID,
    val name: String,
)

data class AccountHierarchyViewState(
    val rows: List<AccountHierarchyRowViewState>,
    /** The grand total, obeying the double-count rule (roots only). Formatted. */
    val totalText: String,
    val baseCurrency: String,
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
