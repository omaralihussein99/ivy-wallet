package com.ivy.shopping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.data.db.dao.read.ShoppingItemDao
import com.ivy.data.db.dao.read.ShoppingListDao
import com.ivy.data.db.dao.write.WriteShoppingItemDao
import com.ivy.data.db.dao.write.WriteShoppingListDao
import com.ivy.data.db.entity.ShoppingListEntity
import com.ivy.navigation.Navigation
import com.ivy.navigation.ShoppingListScreen
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ShoppingListsViewModel @Inject constructor(
    private val navigation: Navigation,
    private val shoppingListDao: ShoppingListDao,
    private val shoppingItemDao: ShoppingItemDao,
    private val writeShoppingListDao: WriteShoppingListDao,
    private val writeShoppingItemDao: WriteShoppingItemDao,
) : ComposeViewModel<ShoppingListsViewState, ShoppingListsEvent>() {

    private var rows by mutableStateOf<List<ShoppingListRowViewState>>(emptyList())
    private var dialogVisible by mutableStateOf(false)
    private var newListName by mutableStateOf("")

    init {
        viewModelScope.launch { reload() }
    }

    @Composable
    override fun uiState(): ShoppingListsViewState = ShoppingListsViewState(
        lists = rows,
        addListDialogVisible = dialogVisible,
        newListName = newListName,
    )

    override fun onEvent(event: ShoppingListsEvent) {
        when (event) {
            ShoppingListsEvent.OnBack -> navigation.back()
            ShoppingListsEvent.OnAddListClick -> {
                newListName = ""
                dialogVisible = true
            }

            ShoppingListsEvent.OnDismissAddList -> dialogVisible = false
            is ShoppingListsEvent.OnNewListNameChange -> newListName = event.name
            ShoppingListsEvent.OnCreateList -> createList()
            is ShoppingListsEvent.OnOpenList -> navigation.navigateTo(ShoppingListScreen(event.listId))
            is ShoppingListsEvent.OnDeleteList -> deleteList(event.listId)
        }
    }

    private fun createList() {
        val name = newListName.trim()
        if (name.isEmpty()) {
            dialogVisible = false
            return
        }
        viewModelScope.launch {
            writeShoppingListDao.save(
                ShoppingListEntity(
                    name = name,
                    color = DefaultListColor,
                    orderNum = rows.size.toDouble(),
                )
            )
            dialogVisible = false
            newListName = ""
            reload()
        }
    }

    private fun deleteList(listId: UUID) {
        viewModelScope.launch {
            // Items first: there is no cascade, so deleting the list alone would orphan them.
            writeShoppingItemDao.deleteByListId(listId)
            writeShoppingListDao.deleteById(listId)
            reload()
        }
    }

    private suspend fun reload() {
        rows = shoppingListDao.findAll().map { list ->
            val items = shoppingItemDao.findByListId(list.id)
            ShoppingListRowViewState(
                id = list.id,
                name = list.name,
                itemsTotal = items.size,
                itemsChecked = items.count { it.isChecked },
                estimatedRemaining = items
                    .filterNot { it.isChecked }
                    .sumOf { (it.unitPrice ?: 0.0) * it.quantity },
            )
        }
    }

    private companion object {
        val DefaultListColor: Int = 0xFF2E7D9A.toInt()
    }
}
