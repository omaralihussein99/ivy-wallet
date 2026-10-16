package com.ivy.backup

/**
 * State of the Backups screen.
 *
 * The screen lists the backups that can actually be restored (the rotation and the "is this file
 * usable" rules live in `shared/data/core` as pure logic), shows where they live, and gates the
 * destructive restore behind a typed confirmation.
 */
data class BackupsViewState(
    val backups: List<BackupRowViewState>,
    val backupDirectoryPath: String,
    val retentionCount: Int,
    val intervalHours: Long,
    val isBusy: Boolean,
    /** The backup the user asked to restore, while the confirmation dialog is up. */
    val restoreCandidate: RestoreCandidateViewState?,
    /** What the user has typed into the confirmation field so far. */
    val restoreConfirmationText: String,
    /** `true` only when the typed text matches the file name exactly: mirrors the typed delete gate. */
    val restoreConfirmed: Boolean,
    val message: BackupMessage?,
)

data class BackupRowViewState(
    val fileName: String,
    /** Already formatted for display (see `BackupDisplayFormat`). */
    val timestampText: String,
    /** Already formatted for display, e.g. `4.5 MB`. Not money, so not `FormatMoneyUseCase`. */
    val sizeText: String,
)

data class RestoreCandidateViewState(
    val fileName: String,
    val timestampText: String,
)

/** Outcomes worth telling the user about; rendered through string resources. */
enum class BackupMessage {
    BACKUP_CREATED,
    BACKUP_FAILED,
    BACKUP_DELETED,
    DELETE_REFUSED,
    RESTORE_NOT_FOUND,
    RESTORE_NOT_USABLE,
    RESTORE_FAILED,
    RESTORE_RESTARTING,
    ;

    val isError: Boolean
        get() = this != BACKUP_CREATED && this != BACKUP_DELETED && this != RESTORE_RESTARTING
}

sealed interface BackupsEvent {
    data object OnBack : BackupsEvent
    data object OnBackUpNow : BackupsEvent
    data object OnRefresh : BackupsEvent
    data object OnDismissMessage : BackupsEvent
    data class OnDeleteClick(val fileName: String) : BackupsEvent
    data class OnRestoreClick(val fileName: String) : BackupsEvent
    data class OnRestoreConfirmationChange(val text: String) : BackupsEvent
    data object OnConfirmRestore : BackupsEvent
    data object OnDismissRestore : BackupsEvent
}
