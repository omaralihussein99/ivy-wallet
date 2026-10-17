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
import com.ivy.ui.FormatMoneyUseCase
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
    /**
     * Money is rendered by Ivy's own formatter, never with String.format.
     */
    private val formatMoney: FormatMoneyUseCase,
) : ComposeViewModel<ShoppingListsViewState, ShoppingListsEvent>() {

    private var templates by mutableStateOf<List<ShoppingListRowViewState>>(emptyList())
    private var trips by mutableStateOf<List<ShoppingListRowViewState>>(emptyList())
    private var dialogVisible by mutableStateOf(false)
    private var newListName by mutableStateOf("")
    private var newListIsTemplate by mutableStateOf(false)

    /**
     * True while a trip is being created. Without it, a second tap (or the same gesture reaching the
     * row as well as the button) creates two trips and the two navigations fight over the screen.
     */
    private var creatingTrip by mutableStateOf(false)

    init {
        viewModelScope.launch { reload() }
    }

    @Composable
    override fun uiState(): ShoppingListsViewState = ShoppingListsViewState(
        templates = templates,
        trips = trips,
        addListDialogVisible = dialogVisible,
        newListName = newListName,
        newListIsTemplate = newListIsTemplate,
    )

    override fun onEvent(event: ShoppingListsEvent) {
        when (event) {
            ShoppingListsEvent.OnBack -> navigation.back()
            ShoppingListsEvent.OnAddListClick -> {
                newListName = ""
                newListIsTemplate = false
                dialogVisible = true
            }

            ShoppingListsEvent.OnDismissAddList -> dialogVisible = false
            is ShoppingListsEvent.OnNewListNameChange -> newListName = event.name
            is ShoppingListsEvent.OnNewListIsTemplateChange -> newListIsTemplate = event.isTemplate
            ShoppingListsEvent.OnCreateList -> createList()
            is ShoppingListsEvent.OnOpenList -> navigation.navigateTo(ShoppingListScreen(event.listId))
            is ShoppingListsEvent.OnDeleteList -> deleteList(event.listId)
            is ShoppingListsEvent.OnNewTripFromTemplate -> createTripFromTemplate(event.templateId)
        }
    }

    private fun createList() {
        val name = newListName.trim()
        if (name.isEmpty()) {
            dialogVisible = false
            return
        }
        val isTemplate = newListIsTemplate
        viewModelScope.launch {
            writeShoppingListDao.save(
                ShoppingListEntity(
                    name = name,
                    color = DefaultListColor,
                    // Written explicitly either way, so the section a list lands in is never a surprise.
                    isTemplate = isTemplate,
                    orderNum = rowCount().toDouble(),
                )
            )
            dialogVisible = false
            newListName = ""
            newListIsTemplate = false
            reload()
        }
    }

    /**
     * "New trip from template": the template's items become a brand new trip, and the template itself
     * is left exactly as it was - it is never ticked and never posted.
     *
     * The trip is written first and navigated to only afterwards, so the screen it opens always has
     * its list and its copied items behind it.
     */
    private fun createTripFromTemplate(templateId: UUID) {
        if (creatingTrip) return
        creatingTrip = true
        viewModelScope.launch {
            try {
                val template = shoppingListDao.findById(templateId) ?: return@launch
                val templateItems = shoppingItemDao.findByListId(templateId)
                val tripId = UUID.randomUUID()
                writeShoppingListDao.save(
                    template.toNewTrip(id = tripId, orderNum = rowCount().toDouble())
                )
                writeShoppingItemDao.save(copiedItemsForTrip(templateItems, tripId))
                reload()
                navigation.navigateTo(ShoppingListScreen(tripId))
            } finally {
                // Also on cancellation: the flag must not stay stuck if the screen goes away.
                creatingTrip = false
            }
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

    private fun rowCount(): Int = templates.size + trips.size

    private suspend fun reload() {
        // The two purpose-built queries keep an archived trip out of the trips section, and a template
        // out of it as well.
        templates = shoppingListDao.findTemplates().map { it.toRowViewState() }
        trips = shoppingListDao.findTrips().map { it.toRowViewState() }
    }

    private suspend fun ShoppingListEntity.toRowViewState(): ShoppingListRowViewState {
        val items = shoppingItemDao.findByListId(id)
        return ShoppingListRowViewState(
            id = id,
            name = name,
            isTemplate = isTemplateList(),
            itemsTotal = items.size,
            itemsChecked = items.checkedCount(),
            estimatedRemainingText = formatMoney.format(
                items.remainingEstimate(),
                shortenAmount = false,
            ),
        )
    }

    private companion object {
        val DefaultListColor: Int = 0xFF2E7D9A.toInt()
    }
}
