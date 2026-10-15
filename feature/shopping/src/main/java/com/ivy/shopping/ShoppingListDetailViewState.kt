package com.ivy.shopping

import java.util.UUID

data class ShoppingListDetailViewState(
    val listName: String,
    val exists: Boolean,
    val items: List<ShoppingItemRowViewState>,
    val itemsChecked: Int,
    val itemsTotal: Int,
    val progress: Float,
    val estimatedRemaining: Double,
    val newItemName: String,
)

data class ShoppingItemRowViewState(
    val id: UUID,
    val name: String,
    val checked: Boolean,
    val quantity: Double,
    val unitPrice: Double?,
)

sealed interface ShoppingListDetailEvent {
    data object OnBack : ShoppingListDetailEvent
    data object OnDeleteList : ShoppingListDetailEvent
    data class OnNewItemNameChange(val name: String) : ShoppingListDetailEvent
    data object OnAddItem : ShoppingListDetailEvent
    data class OnToggleItem(val itemId: UUID) : ShoppingListDetailEvent
    data class OnDeleteItem(val itemId: UUID) : ShoppingListDetailEvent
}
