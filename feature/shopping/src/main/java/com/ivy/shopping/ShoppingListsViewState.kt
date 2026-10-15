package com.ivy.shopping

import java.util.UUID

data class ShoppingListsViewState(
    val lists: List<ShoppingListRowViewState>,
    val addListDialogVisible: Boolean,
    val newListName: String,
)

data class ShoppingListRowViewState(
    val id: UUID,
    val name: String,
    val itemsTotal: Int,
    val itemsChecked: Int,
    val estimatedRemaining: Double,
)

sealed interface ShoppingListsEvent {
    data object OnBack : ShoppingListsEvent
    data object OnAddListClick : ShoppingListsEvent
    data object OnDismissAddList : ShoppingListsEvent
    data class OnNewListNameChange(val name: String) : ShoppingListsEvent
    data object OnCreateList : ShoppingListsEvent
    data class OnOpenList(val listId: UUID) : ShoppingListsEvent
    data class OnDeleteList(val listId: UUID) : ShoppingListsEvent
}
