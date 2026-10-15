package com.ivy.shopping

import java.util.UUID

/**
 * The lists screen is two sections: the templates (the reusable originals) and the trips (the working
 * copies you tick off in the shop).
 */
data class ShoppingListsViewState(
    val templates: List<ShoppingListRowViewState>,
    val trips: List<ShoppingListRowViewState>,
    val addListDialogVisible: Boolean,
    val newListName: String,
    val newListIsTemplate: Boolean,
)

data class ShoppingListRowViewState(
    val id: UUID,
    val name: String,
    val isTemplate: Boolean,
    val itemsTotal: Int,
    val itemsChecked: Int,
    /**
     * Money as text, already formatted with Ivy's `FormatMoneyUseCase` by the view model.
     */
    val estimatedRemainingText: String,
)

sealed interface ShoppingListsEvent {
    data object OnBack : ShoppingListsEvent
    data object OnAddListClick : ShoppingListsEvent
    data object OnDismissAddList : ShoppingListsEvent
    data class OnNewListNameChange(val name: String) : ShoppingListsEvent
    data class OnNewListIsTemplateChange(val isTemplate: Boolean) : ShoppingListsEvent
    data object OnCreateList : ShoppingListsEvent
    data class OnOpenList(val listId: UUID) : ShoppingListsEvent
    data class OnDeleteList(val listId: UUID) : ShoppingListsEvent

    /** Copies a template's items into a brand new trip, leaving the template untouched. */
    data class OnNewTripFromTemplate(val templateId: UUID) : ShoppingListsEvent
}
