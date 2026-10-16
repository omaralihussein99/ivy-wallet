package com.ivy.accounts.hierarchy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R
import com.ivy.ui.component.BackButton
import java.util.UUID

@Composable
fun AccountHierarchyScreenImpl() {
    val viewModel: AccountHierarchyViewModel = screenScopedViewModel()
    val viewState = viewModel.uiState()
    AccountHierarchyScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountHierarchyScreenUi(
    viewState: AccountHierarchyViewState,
    onEvent: (AccountHierarchyEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.account_hierarchy_title)) },
                navigationIcon = {
                    BackButton(onClick = { onEvent(AccountHierarchyEvent.OnBack) })
                },
            )
        },
        content = { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                GrandTotal(
                    totalText = viewState.totalText,
                    baseCurrency = viewState.baseCurrency,
                )
                HorizontalDivider()
                Tree(
                    rows = viewState.rows,
                    onEvent = onEvent,
                )
            }
        },
    )

    if (viewState.parentPickerVisible) {
        ParentPickerDialog(
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

/**
 * The grand total obeys the double-count rule: it is the sum of the rolled-up balances of the root
 * accounts only, so a child is never counted twice.
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
            text = stringResource(R.string.total_balance),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.account_hierarchy_total, totalText, baseCurrency),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Tree(
    rows: List<AccountHierarchyRowViewState>,
    onEvent: (AccountHierarchyEvent) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.account_hierarchy_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = rows, key = { it.id }) { row ->
            AccountRow(
                row = row,
                onToggle = { onEvent(AccountHierarchyEvent.OnToggleExpand(row.id)) },
                onSetParent = { onEvent(AccountHierarchyEvent.OnOpenParentPicker(row.id)) },
            )
            HorizontalDivider()
        }
        item {
            // Keeps the last row clear of the navigation bar.
            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

@Composable
private fun AccountRow(
    row: AccountHierarchyRowViewState,
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
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (row.expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowRight
                    },
                    contentDescription = stringResource(
                        if (row.expanded) R.string.account_hierarchy_collapse else R.string.account_hierarchy_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.parentName != null) {
                Text(
                    text = stringResource(R.string.account_hierarchy_parent, row.parentName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            // Every row shows its OWN balance.
            Text(
                text = stringResource(R.string.account_hierarchy_own, row.ownBalanceText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A parent also shows a rolled-up figure, deliberately distinct from the own balance.
            if (row.hasChildren) {
                Text(
                    text = stringResource(R.string.account_hierarchy_rolled_up, row.rolledUpBalanceText),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        IconButton(onClick = onSetParent) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.account_hierarchy_set_parent),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParentPickerDialog(
    accountName: String,
    options: List<AccountParentOptionViewState>,
    rejected: Boolean,
    onSelect: (UUID?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.account_hierarchy_choose_parent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (rejected) {
                    Text(
                        text = stringResource(R.string.account_hierarchy_cycle_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.account_hierarchy_parent, accountName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))

                // Clearing the parent is always offered, and is always allowed.
                PickerOption(
                    name = stringResource(R.string.account_hierarchy_no_parent),
                    onClick = { onSelect(null) },
                )
                // The options already exclude the account itself and all of its descendants.
                options.forEach { option ->
                    PickerOption(
                        name = option.name,
                        onClick = { onSelect(option.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.account_hierarchy_close))
            }
        },
    )
}

@Composable
private fun PickerOption(
    name: String,
    onClick: () -> Unit,
) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
