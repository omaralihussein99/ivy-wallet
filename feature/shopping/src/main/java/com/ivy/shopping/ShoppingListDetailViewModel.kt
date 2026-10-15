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
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.navigation.Navigation
import com.ivy.navigation.ShoppingListScreen
import com.ivy.ui.ComposeViewModel
import com.ivy.ui.FormatMoneyUseCase
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
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val checkoutService: ShoppingCheckoutService,
    /**
     * Money is rendered by Ivy's own formatter, never with String.format: only this one knows the
     * user's decimal and grouping settings.
     */
    private val formatMoney: FormatMoneyUseCase,
) : ComposeViewModel<ShoppingListDetailViewState, ShoppingListDetailEvent>() {

    /**
     * The screen carries the id, so it is read once while the current screen is still this one.
     */
    private val listId: UUID? = (navigation.currentScreen as? ShoppingListScreen)?.listId

    private var listName by mutableStateOf("")
    private var exists by mutableStateOf(true)
    private var isTemplate by mutableStateOf(false)
    private var items by mutableStateOf<List<ShoppingItemEntity>>(emptyList())
    private var newItemName by mutableStateOf("")

    // Formatted by the view model because FormatMoneyUseCase is a suspend function.
    private var estimatedRemainingText by mutableStateOf("")
    private var itemDetailTexts by mutableStateOf<Map<UUID, String>>(emptyMap())

    // Checkout sheet
    private var listAccountId by mutableStateOf<UUID?>(null)
    private var accounts by mutableStateOf<List<ShoppingPickOption>>(emptyList())
    private var categories by mutableStateOf<List<ShoppingPickOption>>(emptyList())
    private var drafts by mutableStateOf<List<CheckoutDraft>>(emptyList())
    private var draftPrices by mutableStateOf<Map<UUID, String>>(emptyMap())
    private var draftLineTotalTexts by mutableStateOf<Map<UUID, String>>(emptyMap())
    private var checkoutTotalText by mutableStateOf("")
    private var checkoutOpen by mutableStateOf(false)
    private var picker by mutableStateOf<ShoppingPickerViewState?>(null)

    init {
        viewModelScope.launch { reload() }
    }

    @Composable
    override fun uiState(): ShoppingListDetailViewState {
        val checked = items.count { it.isChecked }
        val total = items.size
        val resolved = resolvedCheckoutRows(drafts, draftPrices)
        return ShoppingListDetailViewState(
            listName = listName,
            exists = exists,
            isTemplate = isTemplate,
            items = items.map {
                ShoppingItemRowViewState(
                    id = it.id,
                    name = it.name,
                    checked = it.isChecked,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    detailText = itemDetailTexts[it.id],
                )
            },
            itemsChecked = checked,
            itemsTotal = total,
            progress = if (total == 0) 0f else checked.toFloat() / total.toFloat(),
            estimatedRemainingText = estimatedRemainingText,
            newItemName = newItemName,
            canCheckout = !isTemplate && items.postableItems().isNotEmpty(),
            checkout = if (!checkoutOpen) {
                null
            } else {
                ShoppingCheckoutViewState(
                    rows = drafts.map { draft ->
                        ShoppingCheckoutRowViewState(
                            itemId = draft.itemId,
                            name = draft.name,
                            quantityText = formatQuantity(draft.quantity),
                            priceText = draftPrices[draft.itemId].orEmpty(),
                            accountLabel = accounts
                                .firstOrNull { it.id == draft.accountId }
                                ?.label
                                .orEmpty(),
                            categoryLabel = draft.categoryId?.let { id ->
                                categories.firstOrNull { it.id == id }?.label
                            },
                            lineTotalText = draftLineTotalTexts[draft.itemId].orEmpty(),
                            skipped = parsePriceInput(draftPrices[draft.itemId].orEmpty()) == null,
                            hasAccount = draft.accountId != null,
                        )
                    },
                    totalText = checkoutTotalText,
                    canConfirm = resolved.isNotEmpty(),
                    postableCount = resolved.size,
                    picker = picker,
                )
            },
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
            ShoppingListDetailEvent.OnCheckoutClick -> openCheckout()
            ShoppingListDetailEvent.OnCheckoutDismiss -> closeCheckout()
            is ShoppingListDetailEvent.OnCheckoutPriceChange ->
                setDraftPrice(event.itemId, event.price)

            is ShoppingListDetailEvent.OnCheckoutAccountClick ->
                openPicker(event.itemId, ShoppingPickKind.Account)

            is ShoppingListDetailEvent.OnCheckoutCategoryClick ->
                openPicker(event.itemId, ShoppingPickKind.Category)

            is ShoppingListDetailEvent.OnCheckoutPick -> applyPick(event.itemId, event.optionId)
            ShoppingListDetailEvent.OnCheckoutPickerDismiss -> picker = null
            ShoppingListDetailEvent.OnCheckoutConfirm -> confirmCheckout()
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
        // A template is the reusable original: it is never ticked and never posted.
        if (isTemplate) return
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

    // ------------------------------------------------------------------ checkout sheet

    /**
     * Opens the sheet for every ticked item that has not been posted yet. Each box starts from what is
     * already known, so the user only touches the prices that are actually different at the till.
     */
    private fun openCheckout() {
        if (items.postableItems().isEmpty()) return
        viewModelScope.launch {
            loadPickers()
            val defaultAccountId = defaultCheckoutAccountId(
                listAccountId = listAccountId,
                accountIds = accounts.mapNotNull { it.id },
            )
            val newDrafts = checkoutDrafts(items, defaultAccountId)
            drafts = newDrafts
            draftPrices = newDrafts.associate { it.itemId to priceInputText(it.unitPrice) }
            draftLineTotalTexts = emptyMap()
            checkoutTotalText = ""
            picker = null
            checkoutOpen = true
            refreshCheckoutTexts()
        }
    }

    private fun closeCheckout() {
        checkoutOpen = false
        picker = null
        drafts = emptyList()
        draftPrices = emptyMap()
        draftLineTotalTexts = emptyMap()
        checkoutTotalText = ""
    }

    private fun setDraftPrice(itemId: UUID, price: String) {
        draftPrices = draftPrices + (itemId to price)
        refreshCheckoutTexts()
    }

    private fun openPicker(itemId: UUID, kind: ShoppingPickKind) {
        viewModelScope.launch {
            loadPickers()
            val options = when (kind) {
                ShoppingPickKind.Account -> accounts
                // "No category" is a real choice, so it is offered explicitly instead of hidden.
                ShoppingPickKind.Category -> listOf(ShoppingPickOption(id = null, label = "")) + categories
            }
            picker = ShoppingPickerViewState(itemId = itemId, kind = kind, options = options)
        }
    }

    private fun applyPick(itemId: UUID, optionId: UUID?) {
        val current = picker ?: return
        drafts = drafts.map { draft ->
            if (draft.itemId != itemId) {
                draft
            } else {
                when (current.kind) {
                    ShoppingPickKind.Account -> draft.copy(accountId = optionId)
                    ShoppingPickKind.Category -> draft.copy(categoryId = optionId)
                }
            }
        }
        picker = null
        refreshCheckoutTexts()
    }

    private fun confirmCheckout() {
        val id = listId ?: return
        if (buildDraftLines(drafts, draftPrices, tripName = listName).isEmpty()) return
        viewModelScope.launch {
            val posted = checkoutService.postTripDrafts(
                items = items,
                drafts = drafts,
                priceText = draftPrices,
                tripName = listName,
            )
            if (posted <= 0) return@launch
            val list = shoppingListDao.findById(id)
            if (list != null) {
                // The trip is done, so it is archived and leaves the list of active trips.
                writeShoppingListDao.save(list.copy(isArchived = true))
            }
            closeCheckout()
            navigation.back()
        }
    }

    private suspend fun loadPickers() {
        if (accounts.isEmpty()) {
            accounts = accountRepository.findAll().map {
                ShoppingPickOption(id = it.id.value, label = it.name.value)
            }
        }
        if (categories.isEmpty()) {
            categories = categoryRepository.findAll().map {
                ShoppingPickOption(id = it.id.value, label = it.name.value)
            }
        }
    }

    /**
     * The running total and each line total are formatted here, in a coroutine, because the formatter
     * is a suspend function. The total is never shortened: at the till the exact number is the point.
     */
    private fun refreshCheckoutTexts() {
        viewModelScope.launch { refreshCheckoutTextsNow() }
    }

    private suspend fun refreshCheckoutTextsNow() {
        val resolved = resolvedCheckoutRows(drafts, draftPrices)
        checkoutTotalText = formatMoney.format(resolved.sumOf { it.amount }, shortenAmount = false)
        draftLineTotalTexts = resolved.associate {
            it.draft.itemId to formatMoney.format(it.amount, shortenAmount = false)
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
            isTemplate = false
            items = emptyList()
            estimatedRemainingText = ""
            itemDetailTexts = emptyMap()
            return
        }
        exists = true
        listName = list.name
        isTemplate = list.isTemplateList()
        listAccountId = list.accountId
        items = shoppingItemDao.findByListId(id)
        refreshFormattedTexts()
    }

    /**
     * Renders every amount on the screen through [FormatMoneyUseCase]. Nothing here uses String.format.
     */
    private suspend fun refreshFormattedTexts() {
        estimatedRemainingText = formatMoney.format(items.remainingEstimate(), shortenAmount = false)
        val details = mutableMapOf<UUID, String>()
        for (item in items) {
            val price = item.unitPrice
            details[item.id] = when {
                price == null -> if (item.quantity == 1.0) "" else formatQuantity(item.quantity)
                else -> buildString {
                    append(formatQuantity(item.quantity))
                    append(" × ")
                    append(formatMoney.format(price, shortenAmount = false))
                    append("  =  ")
                    append(formatMoney.format(price * item.quantity, shortenAmount = false))
                }
            }
        }
        itemDetailTexts = details.filterValues { it.isNotEmpty() }
    }
}
