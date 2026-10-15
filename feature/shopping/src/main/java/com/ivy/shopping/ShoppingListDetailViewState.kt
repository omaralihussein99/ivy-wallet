package com.ivy.shopping

import java.util.UUID

data class ShoppingListDetailViewState(
    val listName: String,
    val exists: Boolean,
    val isTemplate: Boolean,
    val items: List<ShoppingItemRowViewState>,
    val itemsChecked: Int,
    val itemsTotal: Int,
    val progress: Float,
    /**
     * Money as text, already formatted with Ivy's `FormatMoneyUseCase` by the view model. The screen
     * never formats money itself, so the user's decimal and grouping settings are always respected.
     */
    val estimatedRemainingText: String,
    val newItemName: String,
    /**
     * A trip may be checked out as soon as something on it is ticked. A template never can - it is
     * never ticked and never posted.
     */
    val canCheckout: Boolean,
    val checkout: ShoppingCheckoutViewState?,
)

data class ShoppingItemRowViewState(
    val id: UUID,
    val name: String,
    val checked: Boolean,
    val quantity: Double,
    val unitPrice: Double?,
    /**
     * "2 × 4.50 = 9.00", already formatted by the view model, or null when there is nothing to say.
     */
    val detailText: String?,
)

/** One box per ticked item on the checkout sheet. */
data class ShoppingCheckoutRowViewState(
    val itemId: UUID,
    val name: String,
    val quantityText: String,
    val priceText: String,
    val accountLabel: String,
    val categoryLabel: String?,
    val lineTotalText: String,
    /** True while the row has no usable price, so it will be skipped instead of posted as zero. */
    val skipped: Boolean,
    val hasAccount: Boolean,
)

data class ShoppingCheckoutViewState(
    val rows: List<ShoppingCheckoutRowViewState>,
    val totalText: String,
    val canConfirm: Boolean,
    /** How many ledger lines the sheet will really write. The rest are skipped, not posted as zero. */
    val postableCount: Int,
    val picker: ShoppingPickerViewState?,
)

/** Accounts and categories are picked from the same dialog, one row of the sheet at a time. */
data class ShoppingPickerViewState(
    val itemId: UUID,
    val kind: ShoppingPickKind,
    val options: List<ShoppingPickOption>,
)

enum class ShoppingPickKind { Account, Category }

/** A pickable account or category. A null [id] is the explicit "no category" choice. */
data class ShoppingPickOption(
    val id: UUID?,
    val label: String,
)

sealed interface ShoppingListDetailEvent {
    data object OnBack : ShoppingListDetailEvent
    data object OnDeleteList : ShoppingListDetailEvent
    data class OnNewItemNameChange(val name: String) : ShoppingListDetailEvent
    data object OnAddItem : ShoppingListDetailEvent
    data class OnToggleItem(val itemId: UUID) : ShoppingListDetailEvent
    data class OnDeleteItem(val itemId: UUID) : ShoppingListDetailEvent
    data object OnCheckoutClick : ShoppingListDetailEvent
    data object OnCheckoutDismiss : ShoppingListDetailEvent
    data class OnCheckoutPriceChange(val itemId: UUID, val price: String) : ShoppingListDetailEvent
    data class OnCheckoutAccountClick(val itemId: UUID) : ShoppingListDetailEvent
    data class OnCheckoutCategoryClick(val itemId: UUID) : ShoppingListDetailEvent
    data class OnCheckoutPick(val itemId: UUID, val optionId: UUID?) : ShoppingListDetailEvent
    data object OnCheckoutPickerDismiss : ShoppingListDetailEvent
    data object OnCheckoutConfirm : ShoppingListDetailEvent
}
