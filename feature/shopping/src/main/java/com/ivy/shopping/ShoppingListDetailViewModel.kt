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
import com.ivy.data.db.entity.ShoppingItemEntity
import com.ivy.navigation.Navigation
import com.ivy.navigation.ShoppingListScreen
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ShoppingListDetailViewModel @Inject constructor(
    private val navigation: Navigation,
    private val shoppingListDao: ShoppingListDao,
    private val shoppingItemDao: ShoppingItemDao,
    private val writeShoppingListDao: WriteShoppingListDao,
    private val writeShoppingItemDao: WriteShoppingItemDao,
) : ComposeViewModel<ShoppingListDetailViewState, ShoppingListDetailEvent>() {

    /**
     * The screen carries the id, so it is read once while the current screen is still this one.
     */
    private val listId: UUID? = (navigation.currentScreen as? ShoppingListScreen)?.listId

    private var listName by mutableStateOf("")
    private var exists by mutableStateOf(true)
    private var items by mutableStateOf<List<ShoppingItemEntity>>(emptyList())
    private var newItemName by mutableStateOf("")

    init {
        viewModelScope.launch { reload() }
    }

    @Composable
    override fun uiState(): ShoppingListDetailViewState {
        val checked = items.count { it.isChecked }
        val total = items.size
        return ShoppingListDetailViewState(
            listName = listName,
            exists = exists,
            items = items.map {
                ShoppingItemRowViewState(
                    id = it.id,
                    name = it.name,
                    checked = it.isChecked,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                )
            },
            itemsChecked = checked,
            itemsTotal = total,
            progress = if (total == 0) 0f else checked.toFloat() / total.toFloat(),
            estimatedRemaining = items
                .filterNot { it.isChecked }
                .sumOf { (it.unitPrice ?: 0.0) * it.quantity },
            newItemName = newItemName,
        )
    }

    override fun onEvent(event: ShoppingListDetailEvent) {
        when (event) {
            ShoppingListDetailEvent.OnBack -> navigation.back()
            ShoppingListDetailEvent.OnDeleteList -> deleteList()
            is ShoppingListDetailEvent.OnNewItemNameChange -> newItemName = event.name
            ShoppingListDetailEvent.OnAddItem -> addItem()
            is ShoppingListDetailEvent.OnToggleItem -> toggleItem(event.itemId)
            is ShoppingListDetailEvent.OnDeleteItem -> deleteItem(event.itemId)
        }
    }

    private fun addItem() {
        val name = newItemName.trim()
        val id = listId
        if (name.isEmpty() || id == null) return
        viewModelScope.launch {
            writeShoppingItemDao.save(
                ShoppingItemEntity(
                    listId = id,
                    name = name,
                    orderNum = items.size.toDouble(),
                )
            )
            newItemName = ""
            reload()
        }
    }

    private fun toggleItem(itemId: UUID) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        viewModelScope.launch {
            writeShoppingItemDao.save(item.copy(isChecked = !item.isChecked))
            reload()
        }
    }

    private fun deleteItem(itemId: UUID) {
        viewModelScope.launch {
            writeShoppingItemDao.deleteById(itemId)
            reload()
        }
    }

    private fun deleteList() {
        val id = listId ?: return
        viewModelScope.launch {
            // Items first: there is no cascade, so the list must not go without them.
            writeShoppingItemDao.deleteByListId(id)
            writeShoppingListDao.deleteById(id)
            navigation.back()
        }
    }

    private suspend fun reload() {
        val id = listId
        if (id == null) {
            exists = false
            return
        }
        val list = shoppingListDao.findById(id)
        if (list == null) {
            exists = false
            listName = ""
            items = emptyList()
            return
        }
        exists = true
        listName = list.name
        items = shoppingItemDao.findByListId(id)
    }
}
