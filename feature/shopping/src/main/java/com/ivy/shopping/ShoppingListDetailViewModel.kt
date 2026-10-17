package com.ivy.shopping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeConverter
import com.ivy.data.db.dao.read.ShoppingItemDao
import com.ivy.data.db.dao.read.ShoppingListDao
import com.ivy.data.db.dao.write.WriteShoppingItemDao
import com.ivy.data.db.dao.write.WriteShoppingListDao
import com.ivy.data.db.entity.ShoppingItemEntity
import com.ivy.data.model.TransactionId
import com.ivy.data.model.getFromValue
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.Navigation
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
    private val transactionRepository: TransactionRepository,
    private val timeConverter: TimeConverter,
    private val checkoutService: ShoppingCheckoutService,
    /**
     * Money is rendered by Ivy's own formatter, never with String.format: only this one knows the
     * user's decimal and grouping settings.
     */
    private val formatMoney: FormatMoneyUseCase,
) : ComposeViewModel<ShoppingListDetailViewState, ShoppingListDetailEvent>() {

    /**
     * The list currently shown. It comes from the screen's argument on every [load], never from the
     * screen that happened to be current when the view model was constructed: the old code read the
     * id once while the screen was still settling, so the screen could stay empty forever.
     */
    private var listId: UUID? = null
    private var loadedListId: UUID? = null

    private var listName by mutableStateOf("")
    private var exists by mutableStateOf(true)
    private var isTemplate by mutableStateOf(false)
    private var items by mutableStateOf<List<ShoppingItemEntity>>(emptyList())
    private var newItemName by mutableStateOf("")
    private var itemEditor by mutableStateOf<ShoppingItemEditorViewState?>(null)

    // Formatted by the view model because FormatMoneyUseCase is a suspend function.
    private var estimatedRemainingText by mutableStateOf("")
    private var itemDetailTexts by mutableStateOf<Map<UUID, String>>(emptyMap())

    // Price history: what the user paid for an item before, restricted to one currency.
    private var postedPrices by mutableStateOf<List<PostedItemPrice>>(emptyList())
    private var accountCurrencies by mutableStateOf<Map<UUID, String>>(emptyMap())
    private var checkoutHistories by mutableStateOf<Map<UUID, PriceHistory>>(emptyMap())
    private var checkoutHistoryPriceTexts by mutableStateOf<Map<UUID, String>>(emptyMap())

    // Where this trip went, when it was posted. Null means "no entry point", never a dead one.
    private var postedTransactionId by mutableStateOf<UUID?>(null)
    private var postedEntryCount by mutableStateOf(0)
    private var postedTransactionMissing by mutableStateOf(false)

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
    private var mode by mutableStateOf(ShoppingCheckoutMode.SingleTransaction)
    private var tripAccountId by mutableStateOf<UUID?>(null)
    private var tripCategoryId by mutableStateOf<UUID?>(null)
    private var refusedEmptyTotal by mutableStateOf(false)
    private var posting by mutableStateOf(false)

    /**
     * Loads the list the screen was opened with.
     *
     * It is a suspending call made from the screen's `LaunchedEffect`, not something fired from this
     * view model's own scope in `init`: the load then belongs to the screen's lifetime and finishes
     * even if the view model was created in the middle of a navigation.
     */
    suspend fun load(requestedListId: UUID) {
        if (!shouldLoadList(loadedListId = loadedListId, requestedListId = requestedListId)) return
        listId = requestedListId
        loadedListId = requestedListId
        // What was paid before never depends on the list being opened, so it is loaded once per
        // screen and reused by the checkout rows and the item editor.
        postedPrices = loadPostedPrices()
        reload()
    }

    @Composable
    override fun uiState(): ShoppingListDetailViewState {
        val checked = items.count { it.isChecked }
        val total = items.size
        val resolved = resolvedCheckoutRows(
            drafts = drafts,
            priceText = draftPrices,
            fallbackAccountId = tripAccountId,
        )
        return ShoppingListDetailViewState(
            listName = listName,
            exists = exists,
            isTemplate = isTemplate,
            postedTransactionId = postedTransactionId,
            postedEntryCount = postedEntryCount,
            postedTransactionDeleted = postedTransactionMissing,
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
                            historyPriceText = checkoutHistoryPriceTexts[draft.itemId],
                            historyTimes = checkoutHistories[draft.itemId]?.times ?: 0,
                            historyCurrency = checkoutHistories[draft.itemId]?.currency,
                        )
                    },
                    totalText = checkoutTotalText,
                    canConfirm = when (mode) {
                        // One row has to be paid from one account, so the trip needs one.
                        ShoppingCheckoutMode.SingleTransaction ->
                            resolved.isNotEmpty() && tripAccountId != null

                        ShoppingCheckoutMode.RowPerItem -> resolved.isNotEmpty()
                    },
                    postableCount = resolved.size,
                    picker = picker,
                    mode = mode,
                    tripAccountLabel = tripAccountId?.let { id ->
                        accounts.firstOrNull { it.id == id }?.label
                    },
                    tripCategoryLabel = tripCategoryId?.let { id ->
                        categories.firstOrNull { it.id == id }?.label
                    },
                    refusedEmptyTotal = refusedEmptyTotal,
                    posting = posting,
                )
            },
            itemEditor = itemEditor,
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
            is ShoppingListDetailEvent.OnEditItem -> openItemEditor(event.itemId)
            is ShoppingListDetailEvent.OnEditorPriceChange -> updateEditor {
                it.copy(priceText = event.price)
            }

            is ShoppingListDetailEvent.OnEditorQuantityChange -> updateEditor {
                it.copy(quantityText = event.quantity)
            }

            ShoppingListDetailEvent.OnEditorSave -> saveItemEditor()
            ShoppingListDetailEvent.OnEditorDismiss -> itemEditor = null
            ShoppingListDetailEvent.OnCheckoutClick -> openCheckout()
            ShoppingListDetailEvent.OnCheckoutDismiss -> closeCheckout()
            is ShoppingListDetailEvent.OnCheckoutModeChange -> setCheckoutMode(event.mode)
            is ShoppingListDetailEvent.OnCheckoutPriceChange ->
                setDraftPrice(event.itemId, event.price)

            is ShoppingListDetailEvent.OnCheckoutAccountClick ->
                openPicker(event.itemId, ShoppingPickKind.Account)

            is ShoppingListDetailEvent.OnCheckoutCategoryClick ->
                openPicker(event.itemId, ShoppingPickKind.Category)

            is ShoppingListDetailEvent.OnCheckoutPick -> applyPick(event.itemId, event.optionId)
            ShoppingListDetailEvent.OnCheckoutPickerDismiss -> picker = null
            ShoppingListDetailEvent.OnCheckoutConfirm -> confirmCheckout()
            ShoppingListDetailEvent.OnOpenPostedTransaction -> openPostedTransaction()
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

    // ------------------------------------------------------------------ editing an item

    /**
     * Setting an item's price (and quantity) is an explicit action from the item's row, and it works
     * the same in a template and in a trip. It is deliberately *not* something that happens when an
     * item is ticked: ticking stays silent.
     */
    private fun openItemEditor(itemId: UUID) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        itemEditor = editorState(
            ShoppingItemEditorViewState(
                itemId = item.id,
                title = item.name,
                priceText = priceInputText(item.unitPrice),
                quantityText = formatQuantity(item.quantity),
                canSave = true,
            )
        )
        // The history hint is filled in afterwards because FormatMoneyUseCase is suspending. It is
        // only a hint: the price box keeps whatever the item already had.
        viewModelScope.launch {
            val currency = listAccountId?.let { accountCurrencies[it] } ?: return@launch
            val history = priceHistory(postedPrices, item.name, currency) ?: return@launch
            val priceText = formatMoney.format(history.lastPrice, shortenAmount = false)
            val dateText = with(timeConverter) { history.lastDate.toLocalDate() }.toString()
            itemEditor = itemEditor
                ?.takeIf { it.itemId == item.id }
                ?.copy(
                    historyPriceText = priceText,
                    historyTimes = history.times,
                    historyCurrency = history.currency,
                    historyDateText = dateText,
                )
        }
    }

    private fun updateEditor(change: (ShoppingItemEditorViewState) -> ShoppingItemEditorViewState) {
        itemEditor = itemEditor?.let { editorState(change(it)) }
    }

    /** Save is available only while both boxes hold something usable. */
    private fun editorState(editor: ShoppingItemEditorViewState): ShoppingItemEditorViewState =
        editor.copy(
            canSave = parsePriceEdit(editor.priceText) != PriceEdit.Invalid &&
                parseQuantityInput(editor.quantityText) != null
        )

    private fun saveItemEditor() {
        val editor = itemEditor ?: return
        // A quantity of zero or a negative amount is refused rather than stored.
        val quantity = parseQuantityInput(editor.quantityText) ?: return
        val price = when (val parsed = parsePriceEdit(editor.priceText)) {
            // An empty box is an explicit "this item has no price yet".
            PriceEdit.Cleared -> null
            is PriceEdit.Priced -> parsed.value
            PriceEdit.Invalid -> return
        }
        val item = items.firstOrNull { it.id == editor.itemId } ?: return
        viewModelScope.launch {
            writeShoppingItemDao.save(item.copy(quantity = quantity, unitPrice = price))
            itemEditor = null
            reload()
        }
    }

    // ------------------------------------------------------------------ checkout sheet

    /**
     * Opens the sheet for every ticked item that has not been posted yet. Each box starts from what is
     * already known, so the user only touches the prices that are actually different at the till.
     *
     * One transaction for the whole trip is the default; the per-item shape is one tap away.
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
            tripAccountId = defaultAccountId
            tripCategoryId = mostCommonCategoryId(items.postableItems())
            mode = ShoppingCheckoutMode.SingleTransaction
            refusedEmptyTotal = false
            posting = false
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
        checkoutHistoryPriceTexts = emptyMap()
        checkoutHistories = emptyMap()
        checkoutTotalText = ""
        refusedEmptyTotal = false
    }

    private fun setCheckoutMode(mode: ShoppingCheckoutMode) {
        this.mode = mode
        refusedEmptyTotal = false
    }

    private fun setDraftPrice(itemId: UUID, price: String) {
        draftPrices = draftPrices + (itemId to price)
        refusedEmptyTotal = false
        refreshCheckoutTexts()
    }

    private fun openPicker(itemId: UUID?, kind: ShoppingPickKind) {
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

    private fun applyPick(itemId: UUID?, optionId: UUID?) {
        val current = picker ?: return
        if (itemId == null) {
            // The whole-trip account/category, used by single-transaction mode.
            when (current.kind) {
                ShoppingPickKind.Account -> tripAccountId = optionId
                ShoppingPickKind.Category -> tripCategoryId = optionId
            }
        } else {
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
        }
        picker = null
        refreshCheckoutTexts()
    }

    /**
     * Posts the trip in the shape the user picked.
     *
     * `posting` is set before the coroutine starts and is only cleared when the post finishes, so a
     * second tap cannot post the trip twice. The service re-reads the items as a second guard.
     */
    private fun confirmCheckout() {
        val id = listId ?: return
        if (posting) return
        posting = true
        refusedEmptyTotal = false
        val modeToPost = mode
        viewModelScope.launch {
            try {
                val posted = when (modeToPost) {
                    ShoppingCheckoutMode.SingleTransaction -> checkoutService.postTripAsOneTransaction(
                        drafts = drafts,
                        priceText = draftPrices,
                        tripName = listName,
                        accountId = tripAccountId,
                        categoryId = tripCategoryId,
                    )

                    ShoppingCheckoutMode.RowPerItem -> checkoutService.postTripDrafts(
                        items = items,
                        drafts = drafts,
                        priceText = draftPrices,
                        tripName = listName,
                    )
                }
                if (posted <= 0) {
                    // Nothing was priced (or there is nobody to pay from): refuse instead of
                    // writing a 0.00 transaction.
                    refusedEmptyTotal = true
                    return@launch
                }
                // Only a trip that is completely in the ledger leaves the list of active trips.
                // Unpriced items keep it open, so nothing the owner bought silently disappears.
                val freshItems = shoppingItemDao.findByListId(id)
                val list = shoppingListDao.findById(id)
                if (list != null && shouldArchiveTrip(freshItems)) {
                    writeShoppingListDao.save(list.copy(isArchived = true))
                }
                closeCheckout()
                navigation.back()
            } finally {
                posting = false
            }
        }
    }

    private suspend fun loadPickers() {
        ensureAccountsLoaded()
        if (categories.isEmpty()) {
            categories = categoryRepository.findAll().map {
                ShoppingPickOption(id = it.id.value, label = it.name.value)
            }
        }
    }

    /** Accounts and their currencies, loaded once: a price is only comparable within one currency. */
    private suspend fun ensureAccountsLoaded() {
        if (accounts.isNotEmpty()) return
        val loaded = accountRepository.findAll()
        accounts = loaded.map { ShoppingPickOption(id = it.id.value, label = it.name.value) }
        accountCurrencies = loaded.associate { it.id.value to it.asset.code }
    }

    /**
     * The running total and each line total are formatted here, in a coroutine, because the formatter
     * is a suspend function. The total is never shortened: at the till the exact number is the point.
     */
    private fun refreshCheckoutTexts() {
        viewModelScope.launch { refreshCheckoutTextsNow() }
    }

    private suspend fun refreshCheckoutTextsNow() {
        val resolved = resolvedCheckoutRows(
            drafts = drafts,
            priceText = draftPrices,
            fallbackAccountId = tripAccountId,
        )
        checkoutTotalText = formatMoney.format(resolved.sumOf { it.amount }, shortenAmount = false)
        draftLineTotalTexts = resolved.associate {
            it.draft.itemId to formatMoney.format(it.amount, shortenAmount = false)
        }

        // The "last paid" hint, restricted to the currency of the account this row is paid from.
        // It is shown next to the price box and never copied into it.
        val histories = mutableMapOf<UUID, PriceHistory>()
        val historyTexts = mutableMapOf<UUID, String>()
        for (draft in drafts) {
            val accountId = draft.accountId ?: tripAccountId ?: continue
            val currency = accountCurrencies[accountId] ?: continue
            val history = priceHistory(postedPrices, draft.name, currency) ?: continue
            histories[draft.itemId] = history
            historyTexts[draft.itemId] = formatMoney.format(history.lastPrice, shortenAmount = false)
        }
        checkoutHistories = histories
        checkoutHistoryPriceTexts = historyTexts
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
        ensureAccountsLoaded()
        refreshFormattedTexts()
        refreshPostedTransactionState()
    }

    /**
     * Where this trip went, when it was posted.
     *
     * Only a trip whose items all point at the SAME transaction (single-transaction checkout) has one
     * transaction to open. A trip posted one row per item has several, so the screen states the count
     * instead of pretending one of them is "the" entry. A stamped id whose transaction no longer
     * exists is reported as deleted, never offered as a dead entry point.
     */
    private suspend fun refreshPostedTransactionState() {
        val transactionIds = items.mapNotNull { it.convertedTransactionId }.distinct()
        postedEntryCount = transactionIds.size
        val single = transactionIds.singleOrNull()
        val exists = single != null &&
            transactionRepository.findById(TransactionId(single)) != null
        postedTransactionId = single?.takeIf { exists }
        postedTransactionMissing = single != null && !exists
    }

    /** Opens the transaction the trip was posted to. Never called without a verified id. */
    private fun openPostedTransaction() {
        val id = postedTransactionId ?: return
        navigation.navigateTo(
            EditTransactionScreen(
                initialTransactionId = id,
                type = TransactionType.EXPENSE,
            )
        )
    }

    /**
     * Everything that was really paid before, from the items that carry a transaction stamp.
     *
     * The currency of a purchase is the asset of the transaction it produced (the account's currency
     * at the time), so history can be restricted to the same currency as the row being priced. An
     * item whose transaction is gone cannot be attributed to a currency and is skipped - the hint
     * would otherwise risk mixing currencies.
     */
    private suspend fun loadPostedPrices(): List<PostedItemPrice> {
        val posted = shoppingItemDao.findAllPosted()
        if (posted.isEmpty()) return emptyList()
        val currencyByTransaction = mutableMapOf<UUID, String?>()
        return posted.mapNotNull { item ->
            val transactionId = item.convertedTransactionId ?: return@mapNotNull null
            val price = item.unitPrice ?: return@mapNotNull null
            if (!currencyByTransaction.containsKey(transactionId)) {
                currencyByTransaction[transactionId] = transactionRepository
                    .findById(TransactionId(transactionId))
                    ?.getFromValue()
                    ?.asset
                    ?.code
            }
            val currency = currencyByTransaction[transactionId] ?: return@mapNotNull null
            PostedItemPrice(
                itemId = item.id.toString(),
                name = item.name,
                unitPrice = price,
                currency = currency,
                postedAt = item.checkedAt ?: item.dateTime,
            )
        }
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
