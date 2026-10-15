package com.ivy.shopping

import com.ivy.data.db.dao.write.WriteShoppingItemDao
import com.ivy.data.db.entity.ShoppingItemEntity
import com.ivy.data.model.AccountId
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.TransactionRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a finished shopping trip to the ledger.
 *
 * Ivy has no multi-line transaction - there is no split support anywhere in the code base - so a trip
 * becomes one expense per ticked item, written together with the trip's account and category. That is
 * the only representation that keeps each item's own price *and* its own category, which is what makes
 * the report afterwards tell the truth.
 *
 * Every item is stamped with [ShoppingItemEntity.convertedTransactionId] as it is posted, so the same
 * trip can never post twice no matter how many times the user taps.
 */
@Singleton
class ShoppingCheckoutService @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val writeShoppingItemDao: WriteShoppingItemDao,
) {

    /**
     * Writes the trip and returns how many ledger lines were actually created.
     *
     * Items that are unticked, already posted, or left without a price are skipped rather than
     * guessed at, so the returned count can be lower than the number of ticked items.
     */
    suspend fun postTrip(
        items: List<ShoppingItemEntity>,
        prices: Map<UUID, Double>,
        accountId: UUID,
        categoryId: UUID?,
    ): Int {
        val lines = buildCheckoutLines(
            items = items,
            prices = prices,
            accountId = accountId,
            categoryId = categoryId,
        )
        var posted = 0
        for (line in lines) {
            val account = accountRepository.findById(AccountId(line.accountId)) ?: continue
            val amount = PositiveDouble.from(line.amount).getOrNull() ?: continue

            val expense = Expense(
                id = TransactionId(UUID.randomUUID()),
                title = NotBlankTrimmedString.from(line.title).getOrNull(),
                description = null,
                category = line.categoryId?.let(::CategoryId),
                time = Instant.now(),
                settled = true,
                metadata = TransactionMetadata(
                    recurringRuleId = null,
                    paidForDateTime = null,
                    loanId = null,
                    loanRecordId = null,
                ),
                tags = emptyList(),
                value = PositiveValue(amount = amount, asset = account.asset),
                account = AccountId(line.accountId),
            )
            transactionRepository.save(expense)

            val item = items.firstOrNull { it.id == line.itemId } ?: continue
            writeShoppingItemDao.save(
                item.copy(
                    // Remember the real price so next week's trip doesn't have to ask again.
                    unitPrice = unitPriceFromLine(line.amount, item.quantity),
                    convertedTransactionId = expense.id.value,
                    checkedAt = item.checkedAt ?: Instant.now(),
                )
            )
            posted++
        }
        return posted
    }
}
