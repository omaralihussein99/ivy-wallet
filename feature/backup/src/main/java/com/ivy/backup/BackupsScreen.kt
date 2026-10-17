package com.ivy.backup

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.hideKeyboard
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R as IvyUiR
import com.ivy.wallet.ui.theme.components.BackBottomBar
import com.ivy.wallet.ui.theme.components.CircleButtonFilled
import com.ivy.wallet.ui.theme.components.DeleteButton
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyNameTextField
import com.ivy.wallet.ui.theme.components.IvyToolbar
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalNegativeButton
import java.util.UUID

@Composable
fun BoxWithConstraintsScope.BackupsScreenImpl() {
    val viewModel: BackupsViewModel = screenScopedViewModel()
    val viewState = viewModel.uiState()
    BackupsScreenUi(
        viewState = viewState,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoxWithConstraintsScope.BackupsScreenUi(
    viewState: BackupsViewState,
    onEvent: (BackupsEvent) -> Unit,
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
                IvyToolbar(onBack = { onEvent(BackupsEvent.OnBack) }) {
                    Spacer(Modifier.weight(1f))

                    CircleButtonFilled(
                        icon = IvyUiR.drawable.ic_refresh,
                        contentDescription = stringResource(R.string.refresh),
                    ) {
                        onEvent(BackupsEvent.OnRefresh)
                    }

                    Spacer(Modifier.width(20.dp))
                }
            }

            item {
                Spacer(Modifier.height(8.dp))

                Text(
                    modifier = Modifier.padding(start = 32.dp),
                    text = stringResource(R.string.backups),
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.Black
                    )
                )

                Spacer(Modifier.height(16.dp))

                viewState.message?.let { message ->
                    MessageBanner(
                        message = message,
                        onDismiss = { onEvent(BackupsEvent.OnDismissMessage) },
                    )
                }

                Caption(text = stringResource(R.string.interval_hint, viewState.intervalHours))
                Caption(text = stringResource(R.string.retention_hint, viewState.retentionCount))
                Caption(text = viewState.backupDirectoryPath)

                Spacer(Modifier.height(8.dp))
            }

            if (viewState.backups.isEmpty()) {
                item {
                    EmptyState()
                }
            } else {
                // Newest first, which is the order the repository hands them out in.
                items(items = viewState.backups, key = { it.fileName }) { row ->
                    BackupCard(
                        row = row,
                        enabled = !viewState.isBusy,
                        onRestore = { onEvent(BackupsEvent.OnRestoreClick(row.fileName)) },
                        onDelete = { onEvent(BackupsEvent.OnDeleteClick(row.fileName)) },
                    )

                    Spacer(Modifier.height(12.dp))
                }
            }

            item {
                // Keeps the last card clear of the bottom bar.
                Spacer(Modifier.height(140.dp))
            }
        }

        BackBottomBar(onBack = { onEvent(BackupsEvent.OnBack) }) {
            IvyButton(
                text = stringResource(R.string.backup_now),
                iconStart = IvyUiR.drawable.ic_save,
                enabled = !viewState.isBusy,
            ) {
                onEvent(BackupsEvent.OnBackUpNow)
            }
        }

        viewState.restoreCandidate?.let { candidate ->
            RestoreConfirmationModal(
                candidate = candidate,
                confirmationText = viewState.restoreConfirmationText,
                restoreConfirmed = viewState.restoreConfirmed,
                isBusy = viewState.isBusy,
                onConfirmationChange = { onEvent(BackupsEvent.OnRestoreConfirmationChange(it)) },
                onConfirm = { onEvent(BackupsEvent.OnConfirmRestore) },
                onDismiss = { onEvent(BackupsEvent.OnDismissRestore) },
            )
        }
    }
}

@Composable
private fun BackupCard(
    row: BackupRowViewState,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    // Settings-style card: 16dp side gutters, `r4` corners and a 2dp medium border.
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .border(2.dp, UI.colors.medium, UI.shapes.r4)
            .clickable(enabled = enabled, onClick = onRestore)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.timestampText,
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.backup_row_subtitle, row.sizeText, row.fileName),
                style = UI.typo.nB2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Normal
                ).copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // `DeleteButton` has no enabled parameter, so the busy state is applied here instead.
        DeleteButton(
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
            onClick = {
                if (enabled) onDelete()
            },
        )
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 2.dp),
        text = text,
        style = UI.typo.nC.style(color = UI.colors.gray),
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
            .padding(horizontal = 32.dp, vertical = 12.dp),
        text = stringResource(message.stringRes()),
        style = UI.typo.nB2.style(
            color = if (message.isError) UI.colors.red else UI.colors.primary,
            fontWeight = FontWeight.Medium
        ),
    )
}

@Composable
private fun EmptyState() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        text = stringResource(R.string.no_backups),
        style = UI.typo.b2.style(
            color = UI.colors.gray,
            fontWeight = FontWeight.Medium
        ),
        textAlign = TextAlign.Center,
    )
}

/**
 * Restore is destructive, so it is behind the same typed-confirmation gate Ivy already uses for
 * deleting an account (`DeleteConfirmationModal`): the confirm button stays disabled until the user
 * has typed the file name of the backup exactly.
 */
@Composable
private fun BoxScope.RestoreConfirmationModal(
    candidate: RestoreCandidateViewState,
    confirmationText: String,
    restoreConfirmed: Boolean,
    isBusy: Boolean,
    onConfirmationChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var deletionTextFieldValue by remember(candidate.fileName) {
        mutableStateOf(TextFieldValue(confirmationText))
    }
    val view = LocalView.current

    // Stable id: a fresh UUID per recomposition would leak modal back handlers.
    val modalId = remember { UUID.randomUUID() }

    IvyModal(
        id = modalId,
        visible = true,
        dismiss = onDismiss,
        PrimaryAction = {
            ModalNegativeButton(
                text = stringResource(R.string.restore),
                iconStart = IvyUiR.drawable.ic_refresh,
                enabled = restoreConfirmed && !isBusy,
            ) {
                onConfirm()
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.restore_backup_title),
            style = UI.typo.b1.style(
                color = UI.colors.red,
                fontWeight = FontWeight.ExtraBold
            )
        )

        Spacer(Modifier.height(24.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.restore_warning),
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.Medium
            )
        )

        Spacer(Modifier.height(12.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.restore_confirm_instruction, candidate.fileName),
            style = UI.typo.nB2.style(color = UI.colors.gray),
        )

        Spacer(Modifier.height(12.dp))

        IvyNameTextField(
            modifier = Modifier.padding(start = 28.dp, end = 36.dp),
            underlineModifier = Modifier.padding(start = 24.dp, end = 32.dp),
            value = deletionTextFieldValue,
            hint = stringResource(R.string.type_file_name_hint),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text,
                autoCorrect = true
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    hideKeyboard(view)
                }
            ),
        ) { newValue ->
            deletionTextFieldValue = newValue
            onConfirmationChange(newValue.text)
        }

        Spacer(Modifier.height(48.dp))
    }
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
