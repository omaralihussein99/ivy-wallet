package com.ivy.shopping

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.component.BackButton

@Composable
fun ShoppingListsScreenImpl() {
    val viewModel: ShoppingListsViewModel = screenScopedViewModel()
    val viewState = viewModel.uiState()
    ShoppingListsScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListsScreenUi(
    viewState: ShoppingListsViewState,
    onEvent: (ShoppingListsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.shopping_lists)) },
                navigationIcon = {
                    BackButton(onClick = { onEvent(ShoppingListsEvent.OnBack) })
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onEvent(ShoppingListsEvent.OnAddListClick) }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_shopping_list),
                )
            }
        },
        content = { innerPadding ->
            Content(
                modifier = Modifier.padding(innerPadding),
                viewState = viewState,
                onEvent = onEvent,
            )
        }
    )

    if (viewState.addListDialogVisible) {
        NewListDialog(
            name = viewState.newListName,
            isTemplate = viewState.newListIsTemplate,
            onNameChange = { onEvent(ShoppingListsEvent.OnNewListNameChange(it)) },
            onIsTemplateChange = { onEvent(ShoppingListsEvent.OnNewListIsTemplateChange(it)) },
            onConfirm = { onEvent(ShoppingListsEvent.OnCreateList) },
            onDismiss = { onEvent(ShoppingListsEvent.OnDismissAddList) },
        )
    }
}

@Composable
private fun Content(
    viewState: ShoppingListsViewState,
    onEvent: (ShoppingListsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (viewState.templates.isEmpty() && viewState.trips.isEmpty()) {
        EmptyState(modifier = modifier)
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (viewState.templates.isNotEmpty()) {
            item(key = "templates_header") {
                SectionHeader(text = stringResource(R.string.section_templates))
            }
            items(items = viewState.templates, key = { it.id }) { row ->
                ShoppingListRow(
                    row = row,
                    onOpen = { onEvent(ShoppingListsEvent.OnOpenList(row.id)) },
                    onDelete = { onEvent(ShoppingListsEvent.OnDeleteList(row.id)) },
                    onNewTrip = { onEvent(ShoppingListsEvent.OnNewTripFromTemplate(row.id)) },
                )
                HorizontalDivider()
            }
        }

        if (viewState.trips.isNotEmpty()) {
            item(key = "trips_header") {
                SectionHeader(text = stringResource(R.string.section_trips))
            }
            items(items = viewState.trips, key = { it.id }) { row ->
                ShoppingListRow(
                    row = row,
                    onOpen = { onEvent(ShoppingListsEvent.OnOpenList(row.id)) },
                    onDelete = { onEvent(ShoppingListsEvent.OnDeleteList(row.id)) },
                    // A trip is already a copy of its template, so it has nothing to start.
                    onNewTrip = null,
                )
                HorizontalDivider()
            }
        }

        item {
            // Keeps the last row clear of the floating action button.
            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ShoppingListRow(
    row: ShoppingListRowViewState,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onNewTrip: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 24.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onNewTrip != null) {
            IconButton(onClick = onNewTrip) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.new_trip_from_template),
                    tint = MaterialTheme.colorScheme.primary,
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

@Composable
private fun ShoppingListRowViewState.subtitle(): String {
    val progress = stringResource(R.string.items_progress, itemsChecked, itemsTotal)
    return if (itemsTotal == 0) {
        progress
    } else {
        "$progress  ·  $estimatedRemainingText"
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.no_shopping_lists),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NewListDialog(
    name: String,
    isTemplate: Boolean,
    onNameChange: (String) -> Unit,
    onIsTemplateChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.new_shopping_list)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.shopping_list_name)) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.save_as_template),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.save_as_template_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isTemplate,
                        onCheckedChange = onIsTemplateChange,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Thin rounded progress track used by the list header. Hand rolled so the appearance is
 * identical across Compose versions instead of depending on the platform indicator.
 */
@Composable
internal fun ShoppingProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
