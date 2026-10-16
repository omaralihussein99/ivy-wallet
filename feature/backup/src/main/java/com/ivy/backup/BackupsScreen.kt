package com.ivy.backup

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.component.BackButton

@Composable
fun BackupsScreenImpl() {
    val viewModel: BackupsViewModel = screenScopedViewModel()
    val viewState = viewModel.uiState()
    BackupsScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreenUi(
    viewState: BackupsViewState,
    onEvent: (BackupsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.backups)) },
                navigationIcon = {
                    BackButton(onClick = { onEvent(BackupsEvent.OnBack) })
                },
                actions = {
                    TextButton(
                        onClick = { onEvent(BackupsEvent.OnBackUpNow) },
                        enabled = !viewState.isBusy,
                    ) {
                        Text(text = stringResource(R.string.backup_now))
                    }
                    IconButton(onClick = { onEvent(BackupsEvent.OnRefresh) }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                },
            )
        },
        content = { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                viewState.message?.let { message ->
                    MessageBanner(
                        message = message,
                        onDismiss = { onEvent(BackupsEvent.OnDismissMessage) },
                    )
                }

                Caption(text = stringResource(R.string.interval_hint, viewState.intervalHours))
                Caption(text = stringResource(R.string.retention_hint, viewState.retentionCount))
                Caption(text = viewState.backupDirectoryPath)

                if (viewState.backups.isEmpty()) {
                    EmptyState()
                } else {
                    BackupList(
                        viewState = viewState,
                        onEvent = onEvent,
                    )
                }
            }
        },
    )

    viewState.restoreCandidate?.let { candidate ->
        RestoreConfirmationDialog(
            candidate = candidate,
            confirmationText = viewState.restoreConfirmationText,
            restoreConfirmed = viewState.restoreConfirmed,
            onConfirmationChange = { onEvent(BackupsEvent.OnRestoreConfirmationChange(it)) },
            onConfirm = { onEvent(BackupsEvent.OnConfirmRestore) },
            onDismiss = { onEvent(BackupsEvent.OnDismissRestore) },
        )
    }
}

@Composable
private fun BackupList(
    viewState: BackupsViewState,
    onEvent: (BackupsEvent) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Newest first, which is the order the repository hands them out in.
        items(items = viewState.backups, key = { it.fileName }) { row ->
            BackupRow(
                row = row,
                enabled = !viewState.isBusy,
                onRestore = { onEvent(BackupsEvent.OnRestoreClick(row.fileName)) },
                onDelete = { onEvent(BackupsEvent.OnDeleteClick(row.fileName)) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun BackupRow(
    row: BackupRowViewState,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.timestampText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.backup_row_subtitle, row.sizeText, row.fileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TextButton(onClick = onRestore, enabled = enabled) {
            Text(text = stringResource(R.string.restore))
        }

        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 2.dp),
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MessageBanner(
    message: BackupMessage,
    onDismiss: () -> Unit,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        text = stringResource(message.stringRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = if (message.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.no_backups),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Restore is destructive, so it is behind the same typed-confirmation gate Ivy already uses for
 * deleting an account (`DeleteConfirmationModal`): the confirm button stays disabled until the user
 * has typed the name of the backup.
 */
@Composable
private fun RestoreConfirmationDialog(
    candidate: RestoreCandidateViewState,
    confirmationText: String,
    restoreConfirmed: Boolean,
    onConfirmationChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.restore_backup_title)) },
        text = {
            Column {
                Text(
                    text = candidate.timestampText,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(R.string.restore_warning))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.restore_confirm_instruction, candidate.fileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmationText,
                    onValueChange = onConfirmationChange,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.type_file_name_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = restoreConfirmed) {
                Text(text = stringResource(R.string.restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

@StringRes
private fun BackupMessage.stringRes(): Int = when (this) {
    BackupMessage.BACKUP_CREATED -> R.string.backup_created
    BackupMessage.BACKUP_FAILED -> R.string.backup_failed
    BackupMessage.BACKUP_DELETED -> R.string.backup_deleted
    BackupMessage.DELETE_REFUSED -> R.string.delete_refused
    BackupMessage.RESTORE_NOT_FOUND -> R.string.restore_not_found
    BackupMessage.RESTORE_NOT_USABLE -> R.string.restore_not_usable
    BackupMessage.RESTORE_FAILED -> R.string.restore_failed
    BackupMessage.RESTORE_RESTARTING -> R.string.restore_restarting
}
