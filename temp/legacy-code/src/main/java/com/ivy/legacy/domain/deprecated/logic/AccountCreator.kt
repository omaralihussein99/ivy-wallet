package com.ivy.legacy.domain.deprecated.logic

import androidx.compose.ui.graphics.toArgb
import arrow.core.raise.either
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.hierarchy.HierarchyNode
import com.ivy.data.hierarchy.ParentAssignment
import com.ivy.data.hierarchy.validateParentAssignment
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.IconAsset
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.legacy.utils.ioThread
import com.ivy.wallet.domain.deprecated.logic.WalletAccountLogic
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData
import com.ivy.wallet.domain.pure.util.nextOrderNum
import java.util.UUID
import javax.inject.Inject
import com.ivy.legacy.datamodel.Account as LegacyAccount

class AccountCreator @Inject constructor(
    private val accountLogic: WalletAccountLogic,
    private val accountDao: AccountDao,
    private val accountRepository: AccountRepository,
    private val currencyRepository: CurrencyRepository,
) {

    suspend fun createAccount(
        data: CreateAccountData,
        onRefreshUI: suspend () -> Unit
    ) {
        ioThread {
            val account = either {
                Account(
                    id = AccountId(value = UUID.randomUUID()),
                    name = NotBlankTrimmedString.from(data.name).bind(),
                    asset = AssetCode.from(data.currency).bind(),
                    color = ColorInt(data.color.toArgb()),
                    icon = data.icon?.let(IconAsset::from)?.getOrNull(),
                    includeInBalance = data.includeBalance,
                    orderNum = accountDao.findMaxOrderNum().nextOrderNum(),
                    parentAccountId = data.parentAccountId?.let(::AccountId),
                )
            }.getOrNull() ?: return@ioThread
            accountRepository.save(account)

            val legacyAccount = LegacyAccount(
                name = data.name,
                currency = data.currency,
                color = data.color.toArgb(),
                icon = data.icon,
                includeInBalance = data.includeBalance,
                orderNum = accountDao.findMaxOrderNum().nextOrderNum(),
                isSynced = false,
                id = account.id.value,
                parentAccountId = data.parentAccountId
            )
            accountLogic.adjustBalance(
                account = legacyAccount,
                actualBalance = 0.0,
                newBalance = data.balance
            )
        }

        onRefreshUI()
    }

    suspend fun editAccount(
        legacyAccount: LegacyAccount,
        newBalance: Double,
        onRefreshUI: suspend () -> Unit
    ) {
        val updatedLegacyAccount = legacyAccount.copy(
            isSynced = false
        )
        ioThread {
            val account = legacyAccount.toDomainAccount(currencyRepository).getOrNull()
                ?: return@ioThread

            // The pure hierarchy rules are the backstop behind the parent picker: the editor must never
            // be able to store a parent that would close a loop. An assignment that the rules reject
            // keeps the parent that is already stored instead of corrupting the hierarchy.
            val storedParentId = accountRepository.findById(account.id)?.parentAccountId
            accountRepository.save(
                account.copy(
                    parentAccountId = checkedParent(
                        requestedParentId = account.parentAccountId,
                        fallbackParentId = storedParentId,
                        accountId = account.id,
                    )
                )
            )

            accountLogic.adjustBalance(
                account = updatedLegacyAccount,
                actualBalance = accountLogic.calculateAccountBalance(updatedLegacyAccount),
                newBalance = newBalance
            )
        }

        onRefreshUI()
    }

    /**
     * Returns [requestedParentId] only when the pure rules allow it, otherwise [fallbackParentId].
     * Clearing the parent (`null`) is always allowed.
     */
    private suspend fun checkedParent(
        requestedParentId: AccountId?,
        fallbackParentId: AccountId?,
        accountId: AccountId,
    ): AccountId? {
        if (requestedParentId == null) return null

        val hierarchy = accountRepository.findAll().map { account ->
            HierarchyNode(
                id = account.id,
                parentId = account.parentAccountId,
            )
        }
        return when (validateParentAssignment(hierarchy, account = accountId, newParent = requestedParentId)) {
            ParentAssignment.Allowed -> requestedParentId
            ParentAssignment.Self, ParentAssignment.Cycle -> fallbackParentId
        }
    }
}
