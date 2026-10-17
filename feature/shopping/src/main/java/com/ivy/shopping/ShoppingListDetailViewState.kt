package com.ivy.shopping

import java.util.UUID

data class ShoppingListDetailViewState(
    val listName: String,
    val exists: Boolean,
    val isTemplate: Boolean,
    /**
     * The one transaction this trip was posted to, when it was posted as a single ledger row **and**
     * that transaction still exists. Null means "no entry point" - never a dead one.
     */
    val postedTransactionId: UUID?,
    /**
     * How many distinct ledger transactions the trip's items point at. 0 = nothing posted yet;
     * more than one = the trip was posted as one row per item, so there is no single transaction to
     * open and the screen only states the fact.
     */
    val postedEntryCount: Int,
    /** True when the trip was posted but the transaction it pointed at no longer exists. */
    val postedTransactionDeleted: Boolean,
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
    /** The explicit "set a price and a quantity" editor, open for one item at a time. */
    val itemEditor: ShoppingItemEditorViewState?,
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

/**
 * Editing an item is an explicit action, opened from the item's own row. It is never a side effect of
 * ticking: ticking stays silent, exactly as before.
 */
data class ShoppingItemEditorViewState(
    val itemId: UUID,
    val title: String,
    val priceText: String,
    val quantityText: String,
    /** False while a field holds something that is not a usable price/quantity. */
    val canSave: Boolean,
    /**
     * "Last paid" hint for this item, or null when it was never posted in the relevant currency.
     * It is a hint only: it never fills the price box.
     */
    val historyPriceText: String? = null,
    val historyTimes: Int = 0,
    val historyCurrency: String? = null,
    val historyDateText: String? = null,
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
    /**
     * What the user paid for this item before, in the row's own currency. A hint only - it is never
     * used as the price and never auto-filled into [priceText].
     */
    val historyPriceText: String? = null,
    val historyTimes: Int = 0,
    val historyCurrency: String? = null,
)

data class ShoppingCheckoutViewState(
    val rows: List<ShoppingCheckoutRowViewState>,
    val totalText: String,
    val canConfirm: Boolean,
    /** How many items the checkout will really post. The rest are skipped, not posted as zero. */
    val postableCount: Int,
    val picker: ShoppingPickerViewState?,
    val mode: ShoppingCheckoutMode,
    /** The one account the whole trip is paid from, in single-transaction mode. */
    val tripAccountLabel: String?,
    /** The one category the whole-trip row gets; null means "no category". */
    val tripCategoryLabel: String?,
    /** Set when Confirm was refused because nothing on the trip has a usable price. */
    val refusedEmptyTotal: Boolean,
    /** True from the moment Confirm is tapped until the post finishes, so it can't be tapped twice. */
    val posting: Boolean,
)

/** Accounts and categories are picked from the same dialog, one row of the sheet at a time. */
data class ShoppingPickerViewState(
    /** The row the pick applies to; null means the pick applies to the whole trip. */
    val itemId: UUID?,
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

    /** Opens the explicit price/quantity editor for one item. */
    data class OnEditItem(val itemId: UUID) : ShoppingListDetailEvent
    data class OnEditorPriceChange(val price: String) : ShoppingListDetailEvent
    data class OnEditorQuantityChange(val quantity: String) : ShoppingListDetailEvent
    data object OnEditorSave : ShoppingListDetailEvent
    data object OnEditorDismiss : ShoppingListDetailEvent

    data object OnCheckoutClick : ShoppingListDetailEvent
    data object OnCheckoutDismiss : ShoppingListDetailEvent
    data class OnCheckoutModeChange(val mode: ShoppingCheckoutMode) : ShoppingListDetailEvent
    data class OnCheckoutPriceChange(val itemId: UUID, val price: String) : ShoppingListDetailEvent
    /** [itemId] is null for the whole-trip account/category used by single-transaction mode. */
    data class OnCheckoutAccountClick(val itemId: UUID?) : ShoppingListDetailEvent
    data class OnCheckoutCategoryClick(val itemId: UUID?) : ShoppingListDetailEvent
    data class OnCheckoutPick(val itemId: UUID?, val optionId: UUID?) : ShoppingListDetailEvent
    data object OnCheckoutPickerDismiss : ShoppingListDetailEvent
    data object OnCheckoutConfirm : ShoppingListDetailEvent

    /** Opens the ledger transaction the trip was posted to, when it still exists. */
    data object OnOpenPostedTransaction : ShoppingListDetailEvent
}
