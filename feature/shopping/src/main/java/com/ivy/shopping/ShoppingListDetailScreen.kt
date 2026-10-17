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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.component.BackButton
import java.util.UUID

@Composable
fun ShoppingListScreenImpl(listId: UUID) {
    val viewModel: ShoppingListDetailViewModel = screenScopedViewModel()
    // The id is the screen's argument, so the list is loaded for it - and loaded again if the same
    // screen is ever shown for another id. Loading used to be captured once, while the screen was
    // still settling, which left the screen empty.
    LaunchedEffect(listId) {
        viewModel.load(listId)
    }
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
                    // Checkout is offered only once something is ticked, and never for a template.
                    if (viewState.canCheckout) {
                        TextButton(onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutClick) }) {
                            Text(text = stringResource(R.string.checkout))
                        }
                    }
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

    viewState.checkout?.let { checkout ->
        CheckoutSheet(sheet = checkout, onEvent = onEvent)
    }

    viewState.itemEditor?.let { editor ->
        ItemEditorDialog(editor = editor, onEvent = onEvent)
    }
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
                        // A template is never ticked, so it shows no checkbox at all.
                        showCheckbox = !viewState.isTemplate,
                        onToggle = { onEvent(ShoppingListDetailEvent.OnToggleItem(row.id)) },
                        onEdit = { onEvent(ShoppingListDetailEvent.OnEditItem(row.id)) },
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
        if (viewState.isTemplate) {
            Text(
                text = stringResource(R.string.template_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.template_items_count, viewState.itemsTotal),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
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
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(
                R.string.estimated_left,
                viewState.estimatedRemainingText,
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
    showCheckbox: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showCheckbox, onClick = onToggle)
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            Checkbox(
                checked = row.checked,
                onCheckedChange = { onToggle() },
            )
        }

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

            val detail = row.detailText
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.edit_item),
            )
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

/**
 * The explicit editor for one item's price and quantity.
 *
 * It is reached from the item's own row and works the same in templates and trips. It is never
 * opened by ticking an item: ticking stays silent.
 */
@Composable
private fun ItemEditorDialog(
    editor: ShoppingItemEditorViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(ShoppingListDetailEvent.OnEditorDismiss) },
        title = { Text(text = editor.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = editor.priceText,
                    onValueChange = {
                        onEvent(ShoppingListDetailEvent.OnEditorPriceChange(it))
                    },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.item_price)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = editor.quantityText,
                    onValueChange = {
                        onEvent(ShoppingListDetailEvent.OnEditorQuantityChange(it))
                    },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.item_quantity)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.item_editor_no_price_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEvent(ShoppingListDetailEvent.OnEditorSave) },
                enabled = editor.canSave,
            ) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ShoppingListDetailEvent.OnEditorDismiss) }) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * One review of everything that is ticked: a box per item with its price, and a choice of how the
 * trip reaches the ledger - one transaction for the whole trip (default) or one row per item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckoutSheet(
    sheet: ShoppingCheckoutViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { onEvent(ShoppingListDetailEvent.OnCheckoutDismiss) },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp),
                text = stringResource(R.string.checkout),
                style = MaterialTheme.typography.titleLarge,
            )

            CheckoutModeSelector(sheet = sheet, onEvent = onEvent)

            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                text = if (sheet.mode == ShoppingCheckoutMode.SingleTransaction) {
                    stringResource(R.string.checkout_summary_single, sheet.postableCount)
                } else {
                    stringResource(R.string.checkout_summary, sheet.postableCount, sheet.rows.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (sheet.mode == ShoppingCheckoutMode.SingleTransaction) {
                SingleTransactionTarget(sheet = sheet, onEvent = onEvent)
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = sheet.rows, key = { it.itemId }) { row ->
                    CheckoutRow(
                        row = row,
                        // The per-row account only matters when every item is its own row.
                        showRowTargets = sheet.mode == ShoppingCheckoutMode.RowPerItem,
                        onEvent = onEvent,
                    )
                    HorizontalDivider()
                }
            }

            if (sheet.refusedEmptyTotal) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    text = stringResource(R.string.checkout_nothing_priced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.checkout_total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = sheet.totalText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                TextButton(onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutDismiss) }) {
                    Text(text = stringResource(R.string.cancel))
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutConfirm) },
                    enabled = sheet.canConfirm && !sheet.posting,
                ) {
                    Text(text = stringResource(R.string.post_to_ledger))
                }
            }
        }
    }

    sheet.picker?.let { picker ->
        PickDialog(picker = picker, onEvent = onEvent)
    }
}

@Composable
private fun CheckoutModeSelector(
    sheet: ShoppingCheckoutViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.checkout_mode_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    onEvent(
                        ShoppingListDetailEvent.OnCheckoutModeChange(
                            ShoppingCheckoutMode.SingleTransaction
                        )
                    )
                },
                enabled = sheet.mode != ShoppingCheckoutMode.SingleTransaction,
            ) {
                Text(text = stringResource(R.string.checkout_mode_single), maxLines = 2)
            }

            Spacer(modifier = Modifier.width(12.dp))

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    onEvent(
                        ShoppingListDetailEvent.OnCheckoutModeChange(
                            ShoppingCheckoutMode.RowPerItem
                        )
                    )
                },
                enabled = sheet.mode != ShoppingCheckoutMode.RowPerItem,
            ) {
                Text(text = stringResource(R.string.checkout_mode_rows), maxLines = 2)
            }
        }
    }
}

/** One account and one category for the whole trip - the price of posting a single row. */
@Composable
private fun SingleTransactionTarget(
    sheet: ShoppingCheckoutViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.checkout_single_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutAccountClick(null)) },
            ) {
                Text(
                    text = sheet.tripAccountLabel
                        ?: stringResource(R.string.checkout_choose_account),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutCategoryClick(null)) },
            ) {
                Text(
                    text = sheet.tripCategoryLabel
                        ?: stringResource(R.string.checkout_no_category),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CheckoutRow(
    row: ShoppingCheckoutRowViewState,
    showRowTargets: Boolean,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = row.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.quantityText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = row.priceText,
                onValueChange = { onEvent(ShoppingListDetailEvent.OnCheckoutPriceChange(row.itemId, it)) },
                singleLine = true,
                label = { Text(text = stringResource(R.string.checkout_price)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                isError = row.skipped,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = if (row.skipped) {
                    stringResource(R.string.checkout_skipped)
                } else {
                    row.lineTotalText
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.skipped) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (showRowTargets) {
            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutAccountClick(row.itemId)) },
                ) {
                    Text(
                        text = if (row.hasAccount) {
                            row.accountLabel
                        } else {
                            stringResource(R.string.checkout_choose_account)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutCategoryClick(row.itemId)) },
                ) {
                    Text(
                        text = row.categoryLabel ?: stringResource(R.string.checkout_no_category),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickDialog(
    picker: ShoppingPickerViewState,
    onEvent: (ShoppingListDetailEvent) -> Unit,
) {
    val title = when (picker.kind) {
        ShoppingPickKind.Account -> stringResource(R.string.checkout_account)
        ShoppingPickKind.Category -> stringResource(R.string.checkout_category)
    }
    AlertDialog(
        onDismissRequest = { onEvent(ShoppingListDetailEvent.OnCheckoutPickerDismiss) },
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (picker.options.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        text = stringResource(R.string.checkout_no_options),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                picker.options.forEach { option ->
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onEvent(
                                    ShoppingListDetailEvent.OnCheckoutPick(
                                        itemId = picker.itemId,
                                        optionId = option.id,
                                    )
                                )
                            }
                            .padding(vertical = 12.dp),
                        text = if (option.id == null) {
                            stringResource(R.string.checkout_no_category)
                        } else {
                            option.label
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(ShoppingListDetailEvent.OnCheckoutPickerDismiss) }) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
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
