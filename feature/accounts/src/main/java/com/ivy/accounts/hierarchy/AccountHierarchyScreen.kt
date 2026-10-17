package com.ivy.accounts.hierarchy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.ui.component.transaction.TransactionsDividerLine
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.components.IvyIcon
import com.ivy.wallet.ui.theme.components.IvyToolbar
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalSet
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID

@Composable
fun BoxWithConstraintsScope.AccountHierarchyScreenImpl() {
    val viewModel: AccountHierarchyViewModel = screenScopedViewModel()
    val viewState = viewModel.uiState()
    AccountHierarchyScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoxWithConstraintsScope.AccountHierarchyScreenUi(
    viewState: AccountHierarchyViewState,
    onEvent: (AccountHierarchyEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // This destination is not legacy, so RootActivity passes includeSurface = false for it. Without
    // painting `pure` here the dark/AMOLED themes would render against an undefined background.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(UI.colors.pure)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            stickyHeader {
                IvyToolbar(onBack = { onEvent(AccountHierarchyEvent.OnBack) })
            }

            item {
                Spacer(Modifier.height(8.dp))

                GrandTotal(
                    totalText = viewState.totalText,
                    baseCurrency = viewState.baseCurrency,
                )

                if (viewState.currencySubtotals.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))

                    CurrencySubtotals(
                        subtotals = viewState.currencySubtotals,
                        baseCurrency = viewState.baseCurrency,
                    )
                }

                if (viewState.excludedCount > 0) {
                    Spacer(Modifier.height(12.dp))

                    Text(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(
                            R.string.account_hierarchy_excluded,
                            viewState.excludedCount,
                        ),
                        style = UI.typo.nC.style(color = UI.colors.red),
                    )
                }

                Spacer(Modifier.height(8.dp))

                TransactionsDividerLine(paddingHorizontal = 24.dp)
            }

            if (viewState.rows.isEmpty()) {
                item {
                    EmptyState()
                }
            } else {
                items(items = viewState.rows, key = { it.id }) { row ->
                    AccountRow(
                        row = row,
                        baseCurrency = viewState.baseCurrency,
                        onToggle = { onEvent(AccountHierarchyEvent.OnToggleExpand(row.id)) },
                        onSetParent = { onEvent(AccountHierarchyEvent.OnOpenParentPicker(row.id)) },
                    )
                    TransactionsDividerLine(paddingHorizontal = 24.dp)
                }
            }

            item {
                // Keeps the last row clear of the navigation bar.
                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        if (viewState.parentPickerVisible) {
            ParentPickerModal(
                accountName = viewState.parentPickerAccountName,
                options = viewState.parentPickerOptions,
                rejected = viewState.parentPickerRejected,
                onSelect = { parentId ->
                    viewState.parentPickerAccountId?.let { accountId ->
                        onEvent(AccountHierarchyEvent.OnSetParent(accountId, parentId))
                    }
                },
                onDismiss = { onEvent(AccountHierarchyEvent.OnDismissParentPicker) },
            )
        }
    }
}

/**
 * The grand total obeys the double-count rule: it is the sum of the rolled-up balances of the root
 * accounts only, so a child is never counted twice. The amount is already formatted by the view
 * model, so it is rendered with the same 30sp `BalanceRow` treatment `AccountsTab` uses rather
 * than being re-formatted here.
 */
@Composable
private fun GrandTotal(
    totalText: String,
    baseCurrency: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.total_balance).uppercase(),
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = totalText,
            style = UI.typo.nH1.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.ExtraBold
            ).copy(fontSize = 30.sp),
        )
        Spacer(Modifier.height(4.dp))
        // The rolled-up figures and this total are base-currency figures, never the parent's own
        // currency - saying so next to them is what makes a mixed-currency tree readable.
        Text(
            text = stringResource(R.string.account_hierarchy_base_note, baseCurrency),
            style = UI.typo.nC.style(color = UI.colors.gray),
        )
    }
}

/**
 * The per-currency view of what the user holds.
 *
 * Each amount is native to its currency and comes from the accounts' OWN balances, so it is honest
 * without any exchange rate. When a currency has no usable rate the whole currency is excluded from
 * the base-currency total and says so, instead of being converted at 1:1 or counted as zero.
 */
@Composable
private fun CurrencySubtotals(
    subtotals: List<CurrencySubtotalViewState>,
    baseCurrency: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = stringResource(R.string.account_hierarchy_by_currency).uppercase(),
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold
            ),
        )

        subtotals.forEach { subtotal ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = subtotal.currency,
                    style = UI.typo.b2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = subtotal.ownTotalText,
                        style = UI.typo.b2.style(
                            color = UI.colors.pureInverse,
                            fontWeight = FontWeight.Medium
                        ),
                    )
                    when {
                        subtotal.baseTotalText != null && subtotal.currency != baseCurrency -> {
                            Text(
                                text = stringResource(
                                    R.string.account_hierarchy_in_base,
                                    subtotal.baseTotalText,
                                ),
                                style = UI.typo.nC.style(color = UI.colors.gray),
                            )
                        }

                        subtotal.baseTotalText == null -> {
                            Text(
                                text = stringResource(R.string.account_hierarchy_no_rate_excluded),
                                style = UI.typo.nC.style(color = UI.colors.red),
                            )
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        text = stringResource(R.string.account_hierarchy_empty),
        style = UI.typo.b2.style(
            color = UI.colors.gray,
            fontWeight = FontWeight.Medium
        ),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun AccountRow(
    row: AccountHierarchyRowViewState,
    baseCurrency: String,
    onToggle: () -> Unit,
    onSetParent: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The indentation is the whole point of the tree: children sit under their parent.
            .padding(start = (12 + row.depth * 20).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.hasChildren) {
            // Same expander as `TransactionSectionDivider`: the shared chevron, rotated when open.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(UI.shapes.rFull)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                IvyIcon(
                    modifier = Modifier.rotate(if (row.expanded) 180f else 0f),
                    icon = R.drawable.ic_expandarrow,
                    tint = UI.colors.gray,
                    contentDescription = stringResource(
                        if (row.expanded) {
                            R.string.account_hierarchy_collapse
                        } else {
                            R.string.account_hierarchy_expand
                        }
                    ),
                )
            }
        } else {
            // Keeps leaf names aligned with their parent's name.
            Spacer(Modifier.size(48.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.parentName != null) {
                Text(
                    text = stringResource(R.string.account_hierarchy_parent, row.parentName),
                    style = UI.typo.nC.style(color = UI.colors.gray),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            // Every row shows its OWN balance, in its OWN currency. It is never a rolled-up figure.
            Text(
                text = stringResource(R.string.account_hierarchy_own, row.ownBalanceText),
                style = UI.typo.nB2.style(color = UI.colors.gray),
            )
            when {
                // No usable rate: the account's money is not in any base-currency figure, and the row
                // says why instead of showing a converted or zeroed number.
                row.noRate -> {
                    Text(
                        text = stringResource(R.string.account_hierarchy_no_rate),
                        style = UI.typo.nB2.style(color = UI.colors.red),
                    )
                }

                // A parent also shows a rolled-up figure, deliberately distinct from the own balance:
                // different type scale, bold, in the primary colour and labelled as base currency.
                row.hasChildren && row.rolledUpBalanceText != null -> {
                    Text(
                        text = stringResource(
                            R.string.account_hierarchy_rolled_up_base,
                            row.rolledUpBalanceText,
                            baseCurrency,
                        ),
                        style = UI.typo.nB1.style(
                            color = UI.colors.primary,
                            fontWeight = FontWeight.Bold
                        ),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(UI.shapes.rFull)
                .clickable(onClick = onSetParent),
            contentAlignment = Alignment.Center,
        ) {
            IvyIcon(
                icon = R.drawable.ic_edit,
                tint = UI.colors.gray,
                contentDescription = stringResource(R.string.account_hierarchy_set_parent),
            )
        }
    }
}

/**
 * The parent picker. Its options are pre-filtered by the view model, and clearing the parent is
 * always the first entry so "no parent" is one tap away.
 */
@Composable
private fun BoxScope.ParentPickerModal(
    accountName: String,
    options: List<AccountParentOptionViewState>,
    rejected: Boolean,
    onSelect: (UUID?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Stable id: a fresh UUID per recomposition would leak modal back handlers.
    val modalId = remember { UUID.randomUUID() }

    IvyModal(
        id = modalId,
        visible = true,
        dismiss = onDismiss,
        PrimaryAction = {
            ModalSet(label = stringResource(R.string.account_hierarchy_close)) {
                onDismiss()
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = stringResource(R.string.account_hierarchy_choose_parent))

        Spacer(Modifier.height(16.dp))

        if (rejected) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                text = stringResource(R.string.account_hierarchy_cycle_error),
                style = UI.typo.nB2.style(color = UI.colors.red),
            )
        }

        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp),
            text = stringResource(R.string.account_hierarchy_parent, accountName),
            style = UI.typo.nC.style(color = UI.colors.gray),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(8.dp))

        // Clearing the parent is always offered, and is always allowed.
        ParentPickerOption(
            name = stringResource(R.string.account_hierarchy_no_parent),
            onClick = { onSelect(null) },
        )
        // The options already exclude the account itself and all of its descendants.
        options.forEach { option ->
            ParentPickerOption(
                name = option.name,
                onClick = { onSelect(option.id) },
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun ParentPickerOption(
    name: String,
    onClick: () -> Unit,
) {
    Text(
        text = name,
        style = UI.typo.b1.style(
            color = UI.colors.pureInverse,
            fontWeight = FontWeight.Normal
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 12.dp),
    )
}
