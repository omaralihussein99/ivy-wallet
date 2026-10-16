package com.ivy.wallet.ui.theme.modal.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ivy.data.hierarchy.HierarchyNode
import com.ivy.data.hierarchy.parentCandidates
import com.ivy.data.model.AccountId
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.domain.legacy.ui.IvyColorPicker
import com.ivy.legacy.IvyWalletPreview
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.utils.isNotNullOrBlank
import com.ivy.legacy.utils.onScreenStart
import com.ivy.legacy.utils.selectEndTextFieldValue
import com.ivy.legacy.utils.toLowerCaseLocal
import com.ivy.legacy.utils.toUpperCaseLocal
import com.ivy.ui.R
import com.ivy.wallet.domain.data.IvyCurrency
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData
import com.ivy.wallet.ui.theme.Gray
import com.ivy.wallet.ui.theme.Ivy
import com.ivy.wallet.ui.theme.components.IvyCheckboxWithText
import com.ivy.wallet.ui.theme.modal.ChooseIconModal
import com.ivy.wallet.ui.theme.modal.CurrencyModal
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalAddSave
import com.ivy.wallet.ui.theme.modal.ModalAmountSection
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID

@Deprecated("Old design system. Use `:ivy-design` and Material3")
data class AccountModalData(
    val account: Account?,
    val baseCurrency: String,
    val balance: Double,
    val adjustBalanceMode: Boolean = false,
    val forceNonZeroBalance: Boolean = false,
    val autoFocusKeyboard: Boolean = true,
    val id: UUID = UUID.randomUUID(),

    /**
     * The accounts the parent picker may roll this account into. Empty means "no hierarchy here", so
     * the parent field is hidden - the callers that are not the account editor (loans, onboarding,
     * planned payments, the bottom-bar add) keep the modal exactly as it was.
     */
    val hierarchyAccounts: List<Account> = emptyList()
)

@Deprecated("Old design system. Use `:ivy-design` and Material3")
@Composable
fun BoxWithConstraintsScope.AccountModal(
    modal: AccountModalData?,
    onCreateAccount: (CreateAccountData) -> Unit,
    onEditAccount: (Account, balance: Double) -> Unit,
    dismiss: () -> Unit,
) {
    val account = modal?.account
    var nameTextFieldValue by remember(modal) {
        mutableStateOf(selectEndTextFieldValue(account?.name))
    }
    var color by remember(modal) {
        mutableStateOf(account?.color?.let { Color(it) } ?: Ivy)
    }
    var amount by remember(modal) {
        mutableStateOf(modal?.balance ?: 0.0)
    }
    var currencyCode by remember(modal) {
        mutableStateOf(account?.currency ?: modal?.baseCurrency ?: "")
    }
    var icon by remember(modal) {
        mutableStateOf(account?.icon)
    }
    var includeInBalance by remember(modal) {
        mutableStateOf(account?.includeInBalance ?: true)
    }
    var parentAccountId by remember(modal) {
        mutableStateOf(account?.parentAccountId)
    }

    var amountModalVisible by remember { mutableStateOf(false) }
    var currencyModalVisible by remember { mutableStateOf(false) }
    var parentModalVisible by remember(modal) {
        mutableStateOf(false)
    }
    var chooseIconModalVisible by remember(modal) {
        mutableStateOf(false)
    }

    val forceNonZeroBalance = modal?.forceNonZeroBalance ?: false

    // The picker can only offer accounts that are neither this account nor one of its own descendants
    // - that is exactly the pure `parentCandidates` rule - so a cycle cannot be created from here.
    val parentChoices = remember(modal, account) {
        val all = modal?.hierarchyAccounts.orEmpty()
        if (all.isEmpty()) {
            emptyList()
        } else {
            val nodes = all.map { candidate ->
                HierarchyNode(
                    id = AccountId(candidate.id),
                    parentId = candidate.parentAccountId?.let(::AccountId)
                )
            }
            val allowed = if (account == null) {
                nodes.map { it.id }.toSet()
            } else {
                parentCandidates(nodes, account = AccountId(account.id)).toSet()
            }
            all.filter { AccountId(it.id) in allowed }
                .sortedBy { it.name.lowercase() }
        }
    }
    val parentName = parentAccountId?.let { parentId ->
        modal?.hierarchyAccounts?.firstOrNull { it.id == parentId }?.name
    }
    val parentFieldVisible = parentChoices.isNotEmpty() || account?.parentAccountId != null

    IvyModal(
        id = modal?.id,
        visible = modal != null,
        dismiss = dismiss,
        shiftIfKeyboardShown = false,
        PrimaryAction = {
            ModalAddSave(
                item = modal?.account,
                enabled = nameTextFieldValue.text.isNotNullOrBlank() && (!forceNonZeroBalance || amount > 0)
            ) {
                save(
                    account = account,
                    nameTextFieldValue = nameTextFieldValue,
                    currency = currencyCode,
                    color = color,
                    icon = icon,
                    amount = amount,
                    includeInBalance = includeInBalance,
                    parentAccountId = parentAccountId,

                    onCreateAccount = onCreateAccount,
                    onEditAccount = onEditAccount,
                    dismiss = dismiss
                )
            }
        }
    ) {
        onScreenStart {
            if (modal?.adjustBalanceMode == true) {
                amountModalVisible = true
            }
        }

        Spacer(Modifier.height(32.dp))

        ModalTitle(
            text = if (modal?.account != null) {
                stringResource(
                    R.string.edit_account
                )
            } else {
                stringResource(R.string.new_account)
            },
        )

        Spacer(Modifier.height(24.dp))

        IconNameRow(
            hint = stringResource(R.string.account_name),
            defaultIcon = R.drawable.ic_custom_account_m,
            color = color,
            icon = icon,

            autoFocusKeyboard = modal?.autoFocusKeyboard ?: true,

            nameTextFieldValue = nameTextFieldValue,
            setNameTextFieldValue = { nameTextFieldValue = it },
            showChooseIconModal = {
                chooseIconModalVisible = true
            }
        )

        Spacer(Modifier.height(24.dp))

        IvyColorPicker(
            selectedColor = color,
            onColorSelected = { color = it }
        )

        if (parentFieldVisible) {
            Spacer(modifier = Modifier.height(24.dp))

            ParentAccountRow(
                parentName = parentName,
                onClick = { parentModalVisible = true }
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        ModalAmountSection(
            Header = {
                Spacer(Modifier.height(16.dp))

                AccountCurrency(
                    currencyCode = currencyCode
                ) {
                    currencyModalVisible = true
                }

                Spacer(modifier = Modifier.height(16.dp))

                IvyCheckboxWithText(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .align(Alignment.Start),
                    text = stringResource(R.string.include_account),
                    checked = includeInBalance
                ) {
                    includeInBalance = it
                }
            },
            label = stringResource(R.string.enter_account_balance).uppercase(),
            currency = currencyCode,
            amount = amount,
            amountPaddingTop = 40.dp,
            amountPaddingBottom = 40.dp,
        ) {
            amountModalVisible = true
        }
    }

    val amountModalId = remember(modal, amount) {
        UUID.randomUUID()
    }
    AmountModal(
        id = amountModalId,
        visible = amountModalVisible,
        currency = currencyCode,
        initialAmount = amount,
        showPlusMinus = true,
        dismiss = { amountModalVisible = false }
    ) { newAmount ->
        amount = newAmount

        if (modal?.adjustBalanceMode == true) {
            save(
                account = account,
                nameTextFieldValue = nameTextFieldValue,
                currency = currencyCode,
                color = color,
                icon = icon,
                amount = newAmount,
                includeInBalance = includeInBalance,
                parentAccountId = parentAccountId,

                onCreateAccount = onCreateAccount,
                onEditAccount = onEditAccount,
                dismiss = dismiss
            )
        }
    }

    val context = LocalContext.current
    CurrencyModal(
        title = stringResource(R.string.choose_currency),
        initialCurrency = IvyCurrency.fromCode(currencyCode),
        visible = currencyModalVisible,
        dismiss = { currencyModalVisible = false }
    ) {
        currencyCode = it

//        if (IvyCurrency.fromCode(it)?.isCrypto == true) {
//            if (getCustomIconId(context = context, iconName = it, size = "m") != null) {
//                icon = it
//            }
//        }
    }

    ChooseIconModal(
        visible = chooseIconModalVisible,
        initialIcon = icon ?: "account",
        color = color,
        dismiss = { chooseIconModalVisible = false }
    ) {
        icon = it
    }

    ChooseParentModal(
        visible = parentModalVisible,
        currentParentId = parentAccountId,
        choices = parentChoices,
        onSelect = {
            parentAccountId = it
            parentModalVisible = false
        },
        dismiss = { parentModalVisible = false }
    )
}

private fun save(
    account: Account?,
    nameTextFieldValue: TextFieldValue,
    currency: String,
    color: Color,
    icon: String?,
    amount: Double,
    includeInBalance: Boolean,
    parentAccountId: UUID?,

    onCreateAccount: (CreateAccountData) -> Unit,
    onEditAccount: (Account, balance: Double) -> Unit,
    dismiss: () -> Unit
) {
    if (account != null) {
        onEditAccount(
            account.copy(
                name = nameTextFieldValue.text.trim(),
                currency = currency,
                includeInBalance = includeInBalance,
                icon = icon,
                color = color.toArgb(),
                parentAccountId = parentAccountId
            ),
            amount
        )
    } else {
        onCreateAccount(
            CreateAccountData(
                name = nameTextFieldValue.text.trim(),
                currency = currency,
                color = color,
                icon = icon,
                balance = amount,
                includeBalance = includeInBalance,
                parentAccountId = parentAccountId
            )
        )
    }

    dismiss()
}

@Composable
private fun AccountCurrency(
    currencyCode: String,

    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .background(UI.colors.medium, UI.shapes.r4)
            .clip(UI.shapes.r4)
            .clickable {
                onClick()
            }
            .padding(vertical = 24.dp)
            .testTag("account_modal_currency"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(32.dp))

        Text(
            text = currencyCode.toUpperCaseLocal(),
            style = UI.typo.b1.style(
                fontWeight = FontWeight.ExtraBold
            )
        )

        Spacer(Modifier.weight(1f))

        val currencyName = IvyCurrency.fromCode(currencyCode)?.name ?: ""
        Text(
            text = "-$currencyName".toLowerCaseLocal(),
            style = UI.typo.b2.style(
                fontWeight = FontWeight.SemiBold,
                color = Gray
            )
        )

        Spacer(Modifier.width(24.dp))
    }
}

@Composable
private fun ParentAccountRow(
    parentName: String?,

    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .background(UI.colors.medium, UI.shapes.r4)
            .clip(UI.shapes.r4)
            .clickable {
                onClick()
            }
            .padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(32.dp))

        Text(
            text = parentName?.let { stringResource(R.string.account_hierarchy_parent, it) }
                ?: stringResource(R.string.account_hierarchy_no_parent),
            style = UI.typo.b1.style(
                fontWeight = FontWeight.ExtraBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(Modifier.width(24.dp))
    }
}

/**
 * The parent picker. Its options are pre-filtered by `parentCandidates`, and clearing the parent is
 * always the first entry so "no parent" is one tap away.
 */
@Composable
private fun ChooseParentModal(
    visible: Boolean,
    currentParentId: UUID?,
    choices: List<Account>,
    onSelect: (UUID?) -> Unit,
    dismiss: () -> Unit
) {
    if (!visible) {
        return
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(
                text = stringResource(R.string.account_hierarchy_choose_parent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ParentPickerOption(
                    name = stringResource(R.string.account_hierarchy_no_parent),
                    selected = currentParentId == null
                ) {
                    onSelect(null)
                }

                choices.forEach { candidate ->
                    ParentPickerOption(
                        name = candidate.name,
                        selected = candidate.id == currentParentId
                    ) {
                        onSelect(candidate.id)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) {
                Text(text = stringResource(R.string.account_hierarchy_close))
            }
        },
    )
}

@Composable
private fun ParentPickerOption(
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = name,
        style = UI.typo.b1.style(
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
            color = if (selected) Ivy else UI.colors.pureInverse
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

@Preview
@Composable
private fun Preview() {
    IvyWalletPreview {
        AccountModal(
            modal = AccountModalData(
                account = null,
                baseCurrency = "BGN",
                balance = 0.0
            ),
            onCreateAccount = { },
            onEditAccount = { _, _ -> }
        ) {
        }
    }
}
