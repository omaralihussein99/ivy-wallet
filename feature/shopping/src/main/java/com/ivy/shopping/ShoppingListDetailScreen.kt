package com.ivy.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.component.BackButton

@Composable
fun ShoppingListScreenImpl() {
    val viewModel: ShoppingListDetailViewModel = screenScopedViewModel()
    val viewState = viewModel.uiState()
    ShoppingListScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreenUi(
    viewState: ShoppingListDetailViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = viewState.listName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    BackButton(onClick = { onEvent(ShoppingListDetailEvent.OnBack) })
                },
                actions = {
                    if (viewState.exists) {
                        IconButton(onClick = { onEvent(ShoppingListDetailEvent.OnDeleteList) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete_list),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        content = { innerPadding ->
            if (!viewState.exists) {
                MissingList(modifier = Modifier.padding(innerPadding))
            } else {
                Content(
                    modifier = Modifier.padding(innerPadding),
                    viewState = viewState,
                    onEvent = onEvent,
                )
            }
        }
    )
}

@Composable
private fun Content(
    viewState: ShoppingListDetailViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Header(viewState = viewState)

        AddItemRow(
            value = viewState.newItemName,
            onValueChange = { onEvent(ShoppingListDetailEvent.OnNewItemNameChange(it)) },
            onAdd = { onEvent(ShoppingListDetailEvent.OnAddItem) },
        )

        HorizontalDivider()

        if (viewState.items.isEmpty()) {
            Text(
                modifier = Modifier.padding(24.dp),
                text = stringResource(R.string.no_shopping_items),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = viewState.items, key = { it.id }) { row ->
                    ItemRow(
                        row = row,
                        onToggle = { onEvent(ShoppingListDetailEvent.OnToggleItem(row.id)) },
                        onDelete = { onEvent(ShoppingListDetailEvent.OnDeleteItem(row.id)) },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(viewState: ShoppingListDetailViewState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(
                R.string.items_progress,
                viewState.itemsChecked,
                viewState.itemsTotal,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        ShoppingProgressBar(progress = viewState.progress)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(
                R.string.estimated_left,
                formatAmount(viewState.estimatedRemaining),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun AddItemRow(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(text = stringResource(R.string.item_name)) },
        )

        IconButton(onClick = onAdd) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.add_item),
            )
        }
    }
}

@Composable
private fun ItemRow(
    row: ShoppingItemRowViewState,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = row.checked,
            onCheckedChange = { onToggle() },
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (row.checked) TextDecoration.LineThrough else null,
                color = if (row.checked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val detail = row.detail()
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun ShoppingItemRowViewState.detail(): String? {
    val price = unitPrice
    return when {
        price == null -> if (quantity == 1.0) null else formatQuantity(quantity)
        else -> "${formatQuantity(quantity)} × ${formatAmount(price)}  =  ${
            formatAmount(price * quantity)
        }"
    }
}

@Composable
private fun MissingList(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.shopping_list_gone),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
