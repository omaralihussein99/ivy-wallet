package com.ivy.accounts.hierarchy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.data.hierarchy.HierarchyNode
import com.ivy.data.hierarchy.ParentAssignment
import com.ivy.data.hierarchy.RateRow
import com.ivy.data.hierarchy.RateTable
import com.ivy.data.hierarchy.accountsExcludedFromBaseTotal
import com.ivy.data.hierarchy.baseGrandTotal
import com.ivy.data.hierarchy.baseRolledUpBalances
import com.ivy.data.hierarchy.hierarchyRows
import com.ivy.data.hierarchy.parentCandidates
import com.ivy.data.hierarchy.validateParentAssignment
import com.ivy.data.hierarchy.visibleRows
import com.ivy.data.hierarchy.currencySubtotals as computeCurrencySubtotals
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import com.ivy.ui.FormatMoneyUseCase
import com.ivy.wallet.domain.action.account.CalcAccBalanceAct
import com.ivy.wallet.domain.action.settings.BaseCurrencyAct
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

/**
 * The account-hierarchy screen.
 *
 * All hierachy maths is delegated to the pure rules in `com.ivy.data.hierarchy`; this class only
 * loads accounts and rates, works out what each number means and formats it with Ivy's
 * `FormatMoneyUseCase`. Every balance stays a `BigDecimal` until it is handed to the formatter - the
 * one place Ivy's formatter accepts a `Double` - so no `Double` arithmetic touches money.
 *
 * Two different questions are answered separately, on purpose:
 *  - each row's own balance is shown **in its own currency** (native, never converted);
 *  - rolled-up and grand-total figures are shown in the **base currency**, and an account whose
 *    currency has no usable rate is excluded from them and marked "no rate" instead of being
 *    counted as 0 or converted at 1:1.
 */
@HiltViewModel
class AccountHierarchyViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val calcAccBalanceAct: CalcAccBalanceAct,
    private val exchangeRatesRepository: ExchangeRatesRepository,
    private val baseCurrencyAct: BaseCurrencyAct,
    private val formatMoney: FormatMoneyUseCase,
    private val navigation: Navigation,
) : ComposeViewModel<AccountHierarchyViewState, AccountHierarchyEvent>() {

    private var rows by mutableStateOf<List<AccountHierarchyRowViewState>>(emptyList())
    private var totalText by mutableStateOf("")
    private var baseCurrency by mutableStateOf("")
    private var subtotals by mutableStateOf<List<CurrencySubtotalViewState>>(emptyList())
    private var excludedCount by mutableIntStateOf(0)

    private var pickerVisible by mutableStateOf(false)
    private var pickerAccountId by mutableStateOf<AccountId?>(null)
    private var pickerOptions by mutableStateOf<List<AccountParentOptionViewState>>(emptyList())
    private var pickerRejected by mutableStateOf(false)

    private var accounts by mutableStateOf<List<Account>>(emptyList())
    private var hierarchyNodes: List<HierarchyNode> = emptyList()
    private var rateTable: RateTable = RateTable(baseCurrency = "")
    private var collapsed by mutableStateOf<Set<AccountId>>(emptySet())

    init {
        viewModelScope.launch { reload() }
    }

    @Composable
    override fun uiState(): AccountHierarchyViewState = AccountHierarchyViewState(
        rows = rows,
        totalText = totalText,
        baseCurrency = baseCurrency,
        currencySubtotals = subtotals,
        excludedCount = excludedCount,
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
        // The rate model has no timestamp, so "usable" is the only freshness signal it carries.
        // `HierarchyCurrency.kt` documents this and makes the decision.
        rateTable = RateTable(
            baseCurrency = baseCurrency,
            rows = exchangeRatesRepository.findAll().first().map { rate ->
                RateRow(
                    baseCurrency = rate.baseCurrency.code,
                    currency = rate.currency.code,
                    rate = rate.rate.value,
                )
            },
        )
        hierarchyNodes = accounts.map { account ->
            HierarchyNode(
                id = account.id,
                parentId = account.parentAccountId,
                // OWN balance, in the account's own currency: conversion happens in the pure rules,
                // and a missing rate excludes the account instead of zeroing it.
                ownBalance = calcAccBalanceAct(CalcAccBalanceAct.Input(account = account)).balance,
                orderNum = account.orderNum,
                currency = account.asset.code,
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
            totalText = formatMoneyText(BigDecimal.ZERO, baseCurrency)
            subtotals = emptyList()
            excludedCount = 0
            return
        }

        val excluded = accountsExcludedFromBaseTotal(hierarchyNodes, rateTable)
        val rolledUp = baseRolledUpBalances(hierarchyNodes, rateTable)
        val currencyById = accounts.associate { it.id to it.asset.code }

        rows = visibleRows(hierarchyRows(hierarchyNodes), collapsed).map { row ->
            val currency = currencyById[row.id].orEmpty()
            val noRate = row.id in excluded
            AccountHierarchyRowViewState(
                id = row.id.value,
                name = nameById[row.id].orEmpty(),
                depth = row.depth,
                // Native, in the account's own currency - clearly not a rolled-up figure.
                ownBalanceText = formatMoneyText(row.ownBalance, currency),
                currency = currency,
                // Base currency, labelled. Null when there is no usable rate: the UI marks it.
                rolledUpBalanceText = if (noRate) {
                    null
                } else {
                    formatMoneyText(rolledUp[row.id] ?: row.ownBalance, baseCurrency)
                },
                parentName = row.parentId?.let { nameById[it] },
                hasChildren = row.hasChildren,
                expanded = row.hasChildren && collapsed.none { it == row.id },
                noRate = noRate,
            )
        }
        // Roots only - a child is already inside its parent's rolled-up figure, and excluded
        // accounts are not in it at all.
        totalText = formatMoneyText(baseGrandTotal(hierarchyNodes, rateTable), baseCurrency)
        excludedCount = excluded.size
        subtotals = computeCurrencySubtotals(hierarchyNodes, rateTable).map { subtotal ->
            CurrencySubtotalViewState(
                currency = subtotal.currency,
                ownTotalText = formatMoneyText(subtotal.ownTotal, subtotal.currency),
                baseTotalText = subtotal.ownTotalInBase
                    ?.let { formatMoneyText(it, baseCurrency) },
                includedInBaseTotal = subtotal.includedInBaseTotal,
            )
        }
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
     * Formats an amount with Ivy's formatter and appends the currency code it is expressed in, so a
     * number can never be read as if it were in another currency. The symbol is not added: Ivy's
     * formatter only knows the user's decimal and grouping settings, and the code is unambiguous.
     */
    private suspend fun formatMoneyText(value: BigDecimal, currency: String): String {
        val text = formatMoney.format(value.toDouble(), shortenAmount = false)
        return if (currency.isBlank()) text else "$text $currency"
    }
}
