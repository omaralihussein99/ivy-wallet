package com.ivy.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.data.backup.local.BackupDisplayFormat
import com.ivy.data.backup.local.BackupEntry
import com.ivy.data.backup.local.LocalBackupConfig
import com.ivy.data.backup.local.LocalBackupRepository
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Backups screen.
 *
 * All the rules about which files are backups, which are usable and which get rotated away are pure
 * functions in `shared/data/core` (`BackupFileName`, `BackupUsability`, `BackupRotation`); this view
 * model only renders their result and forwards the user's decisions to [LocalBackupRepository].
 */
@HiltViewModel
class BackupsViewModel @Inject constructor(
    private val navigation: Navigation,
    private val repository: LocalBackupRepository,
) : ComposeViewModel<BackupsViewState, BackupsEvent>() {

    private var backups by mutableStateOf<List<BackupRowViewState>>(emptyList())
    private var directoryPath by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var candidate by mutableStateOf<RestoreCandidateViewState?>(null)
    private var confirmationText by mutableStateOf("")
    private var message by mutableStateOf<BackupMessage?>(null)

    init {
        directoryPath = repository.backupDirectoryPath()
        viewModelScope.launch { reload() }
    }

    @Composable
    override fun uiState(): BackupsViewState = BackupsViewState(
        backups = backups,
        backupDirectoryPath = directoryPath,
        retentionCount = LocalBackupConfig.RETENTION_COUNT,
        intervalHours = LocalBackupConfig.BACKUP_INTERVAL_HOURS,
        isBusy = busy,
        restoreCandidate = candidate,
        restoreConfirmationText = confirmationText,
        restoreConfirmed = isConfirmationMatching(),
        message = message,
    )

    override fun onEvent(event: BackupsEvent) {
        when (event) {
            BackupsEvent.OnBack -> navigation.back()

            BackupsEvent.OnBackUpNow -> backUpNow()
            BackupsEvent.OnRefresh -> viewModelScope.launch { reload() }
            BackupsEvent.OnDismissMessage -> message = null

            is BackupsEvent.OnDeleteClick -> delete(event.fileName)
            is BackupsEvent.OnRestoreClick -> askToRestore(event.fileName)
            is BackupsEvent.OnRestoreConfirmationChange -> confirmationText = event.text
            BackupsEvent.OnConfirmRestore -> confirmRestore()
            BackupsEvent.OnDismissRestore -> dismissRestore()
        }
    }

    /**
     * Manual backup. Runs the very same repository call the periodic worker runs, in the foreground:
     * the user is looking at the screen and gets the result immediately, which the enqueued worker
     * could not give it.
     */
    private fun backUpNow() {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            val created = repository.createBackup()
            message = if (created == null) BackupMessage.BACKUP_FAILED else BackupMessage.BACKUP_CREATED
            reload()
            busy = false
        }
    }

    private fun delete(fileName: String) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            message = if (repository.deleteBackup(fileName)) {
                BackupMessage.BACKUP_DELETED
            } else {
                // The repository refuses to delete a backup a restore is using.
                BackupMessage.DELETE_REFUSED
            }
            reload()
            busy = false
        }
    }

    private fun askToRestore(fileName: String) {
        val row = backups.firstOrNull { it.fileName == fileName } ?: return
        candidate = RestoreCandidateViewState(fileName = row.fileName, timestampText = row.timestampText)
        confirmationText = ""
        message = null
    }

    private fun dismissRestore() {
        candidate = null
        confirmationText = ""
    }

    /**
     * The restore is only reachable once the typed text matches the file name exactly; the button in
     * the dialog is disabled otherwise. Restore replaces the database file and then kills the process
     * (see [LocalBackupRepository.restoreBackup]), so there is no half-applied state to fall back to.
     */
    private fun confirmRestore() {
        val fileName = candidate?.fileName ?: return
        if (!isConfirmationMatching() || busy) return
        busy = true
        candidate = null
        confirmationText = ""
        viewModelScope.launch {
            message = repository.restoreBackup(fileName).toMessage()
            reload()
            busy = false
        }
    }

    private fun isConfirmationMatching(): Boolean {
        val fileName = candidate?.fileName ?: return false
        return confirmationText.trim() == fileName
    }

    private fun LocalBackupRepository.RestoreResult.toMessage(): BackupMessage = when (this) {
        LocalBackupRepository.RestoreResult.RESTARTING -> BackupMessage.RESTORE_RESTARTING
        LocalBackupRepository.RestoreResult.NOT_FOUND -> BackupMessage.RESTORE_NOT_FOUND
        LocalBackupRepository.RestoreResult.NOT_USABLE -> BackupMessage.RESTORE_NOT_USABLE
        LocalBackupRepository.RestoreResult.FAILED -> BackupMessage.RESTORE_FAILED
    }

    private suspend fun reload() {
        backups = repository.listBackups().map { it.toRowViewState() }
    }

    private fun BackupEntry.toRowViewState() = BackupRowViewState(
        fileName = fileName,
        // A listed backup always has a parseable name, but falling back beats showing a wrong time.
        timestampText = BackupDisplayFormat.timestamp(timestampEpochMillis ?: lastModifiedEpochMillis),
        sizeText = BackupDisplayFormat.size(sizeBytes),
    )
}
