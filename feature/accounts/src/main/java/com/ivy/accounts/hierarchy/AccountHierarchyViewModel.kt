package com.ivy.accounts.hierarchy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import arrow.core.toOption
import com.ivy.data.hierarchy.HierarchyNode
import com.ivy.data.hierarchy.ParentAssignment
import com.ivy.data.hierarchy.grandTotal
import com.ivy.data.hierarchy.hierarchyRows
import com.ivy.data.hierarchy.parentCandidates
import com.ivy.data.hierarchy.validateParentAssignment
import com.ivy.data.hierarchy.visibleRows
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import com.ivy.ui.FormatMoneyUseCase
import com.ivy.wallet.domain.action.account.CalcAccBalanceAct
import com.ivy.wallet.domain.action.exchange.ExchangeAct
import com.ivy.wallet.domain.action.settings.BaseCurrencyAct
import com.ivy.wallet.domain.pure.exchange.ExchangeData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

/**
 * The account-hierarchy screen.
 *
 * All hierachy maths is delegated to the pure rules in `com.ivy.data.hierarchy`; this class only
 * loads accounts, works out each account's OWN balance in the base currency and formats the numbers
 * with Ivy's `FormatMoneyUseCase`. Every balance stays a `BigDecimal` until it is handed to the
 * formatter - the one place Ivy's formatter accepts a `Double` - so no `Double` arithmetic touches
 * money.
 */
@HiltViewModel
class AccountHierarchyViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val calcAccBalanceAct: CalcAccBalanceAct,
    private val exchangeAct: ExchangeAct,
    private val baseCurrencyAct: BaseCurrencyAct,
    private val formatMoney: FormatMoneyUseCase,
    private val navigation: Navigation,
) : ComposeViewModel<AccountHierarchyViewState, AccountHierarchyEvent>() {

    private var rows by mutableStateOf<List<AccountHierarchyRowViewState>>(emptyList())
    private var totalText by mutableStateOf("")
    private var baseCurrency by mutableStateOf("")

    private var pickerVisible by mutableStateOf(false)
    private var pickerAccountId by mutableStateOf<AccountId?>(null)
    private var pickerOptions by mutableStateOf<List<AccountParentOptionViewState>>(emptyList())
    private var pickerRejected by mutableStateOf(false)

    private var accounts by mutableStateOf<List<Account>>(emptyList())
    private var hierarchyNodes: List<HierarchyNode> = emptyList()
    private var collapsed by mutableStateOf<Set<AccountId>>(emptySet())

    init {
        viewModelScope.launch { reload() }
    }

    @Composable
    override fun uiState(): AccountHierarchyViewState = AccountHierarchyViewState(
        rows = rows,
        totalText = totalText,
        baseCurrency = baseCurrency,
        parentPickerVisible = pickerVisible,
        parentPickerAccountId = pickerAccountId?.value,
        parentPickerAccountName = accounts.firstOrNull { it.id == pickerAccountId }?.name?.value.orEmpty(),
        parentPickerOptions = pickerOptions,
        parentPickerRejected = pickerRejected,
    )

    override fun onEvent(event: AccountHierarchyEvent) {
        when (event) {
            AccountHierarchyEvent.OnBack -> navigation.back()
            is AccountHierarchyEvent.OnToggleExpand -> toggleExpand(event.accountId)
            is AccountHierarchyEvent.OnOpenParentPicker -> openParentPicker(event.accountId)
            AccountHierarchyEvent.OnDismissParentPicker -> dismissParentPicker()
            is AccountHierarchyEvent.OnSetParent -> setParent(event.accountId, event.parentId)
        }
    }

    // ------------------------------------------------------------------ the tree

    private suspend fun reload() {
        baseCurrency = baseCurrencyAct(Unit)
        accounts = accountRepository.findAll()
        hierarchyNodes = accounts.map { account ->
            HierarchyNode(
                id = account.id,
                parentId = account.parentAccountId,
                ownBalance = ownBalanceInBaseCurrency(account, baseCurrency),
                orderNum = account.orderNum,
            )
        }
        // A parent that is gone must not leave a stale "collapsed" flag behind.
        collapsed = collapsed.filter { id -> accounts.any { it.id == id } }.toSet()
        rebuildTree()
    }

    private suspend fun rebuildTree() {
        val nameById: Map<AccountId, String> = accounts.associate { it.id to it.name.value }
        if (hierarchyNodes.isEmpty()) {
            rows = emptyList()
            totalText = formatMoneyText(BigDecimal.ZERO)
            return
        }

        rows = visibleRows(hierarchyRows(hierarchyNodes), collapsed).map { row ->
            AccountHierarchyRowViewState(
                id = row.id.value,
                name = nameById[row.id].orEmpty(),
                depth = row.depth,
                ownBalanceText = formatMoneyText(row.ownBalance),
                rolledUpBalanceText = formatMoneyText(row.rolledUpBalance),
                parentName = row.parentId?.let { nameById[it] },
                hasChildren = row.hasChildren,
                expanded = row.hasChildren && collapsed.none { it == row.id },
            )
        }
        // Roots only - a child is already inside its parent's rolled-up figure.
        totalText = formatMoneyText(grandTotal(hierarchyNodes))
    }

    private fun toggleExpand(accountId: UUID) {
        val id = AccountId(accountId)
        collapsed = if (collapsed.contains(id)) collapsed - id else collapsed + id
        viewModelScope.launch { rebuildTree() }
    }

    // ------------------------------------------------------------------ setting the parent

    private fun openParentPicker(accountId: UUID) {
        val id = AccountId(accountId)
        // The picker hides the account itself and every one of its descendants, which is what makes a
        // cycle impossible through the UI.
        val candidates = parentCandidates(hierarchyNodes, id).toSet()
        pickerAccountId = id
        pickerRejected = false
        pickerOptions = accounts
            .filter { it.id in candidates }
            .sortedBy { it.name.value.lowercase() }
            .map { AccountParentOptionViewState(id = it.id.value, name = it.name.value) }
        pickerVisible = true
    }

    private fun dismissParentPicker() {
        pickerVisible = false
        pickerAccountId = null
        pickerRejected = false
    }

    private fun setParent(accountId: UUID, parentId: UUID?) {
        val id = AccountId(accountId)
        val newParent = parentId?.let(::AccountId)

        // The pure validator is the backstop behind the picker.
        when (validateParentAssignment(hierarchyNodes, account = id, newParent = newParent)) {
            ParentAssignment.Self, ParentAssignment.Cycle -> {
                pickerRejected = true
                return
            }

            ParentAssignment.Allowed -> Unit
        }

        viewModelScope.launch {
            val account = accountRepository.findById(id) ?: return@launch
            // Saving the whole account keeps the parent id, because the mapper carries it both ways.
            accountRepository.save(account.copy(parentAccountId = newParent))
            dismissParentPicker()
            reload()
        }
    }

    // ------------------------------------------------------------------ money

    /**
     * The account's own balance, converted to the base currency. Own means this account alone - the
     * children are added by the roll-up, never here.
     */
    private suspend fun ownBalanceInBaseCurrency(account: Account, baseCurrency: String): BigDecimal {
        val own = calcAccBalanceAct(CalcAccBalanceAct.Input(account = account)).balance
        if (own.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        if (account.asset.code == baseCurrency) return own

        // An account whose currency cannot be exchanged is left at zero rather than valued at a
        // made-up rate; the total then understates instead of lying.
        return exchangeAct(
            ExchangeAct.Input(
                data = ExchangeData(
                    baseCurrency = baseCurrency,
                    fromCurrency = account.asset.code.toOption(),
                    toCurrency = baseCurrency,
                ),
                amount = own,
            )
        ).orNull() ?: BigDecimal.ZERO
    }

    /**
     * The single place a balance becomes a `Double`: Ivy's formatter takes one. No arithmetic happens
     * here, and every sum above stayed a `BigDecimal`.
     */
    private suspend fun formatMoneyText(value: BigDecimal): String =
        formatMoney.format(value.toDouble(), shortenAmount = false)
}
