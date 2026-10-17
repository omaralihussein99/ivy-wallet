package com.ivy.shopping

import com.ivy.data.db.dao.read.ShoppingItemDao
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
 * Posts a finished shopping trip to the ledger, in one of the two shapes the user picks at checkout:
 *
 *  - **one transaction for the whole trip** (the default): a single expense titled with the trip's
 *    name, its amount being the sum of the priced items, filed under one account and one category.
 *    Every item that contributed is stamped with that transaction's id in
 *    [ShoppingItemEntity.convertedTransactionId], which is how the transaction screen finds the items
 *    to show as the breakdown. No new column is needed for the link.
 *  - **one expense per item**: the shape the feature shipped with. The row is titled with the trip's
 *    name and described with the item's name, and it keeps that item's own category.
 *
 * Both shapes stamp every item they post with [ShoppingItemEntity.convertedTransactionId], so an item
 * can never be paid for twice - not by tapping twice and not by posting the same trip again in the
 * other mode. An item that was skipped (unpriced) keeps a null stamp, which is why it stays on the
 * trip and can be posted later once it has a price.
 */
@Singleton
class ShoppingCheckoutService @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val shoppingItemDao: ShoppingItemDao,
    private val writeShoppingItemDao: WriteShoppingItemDao,
) {

    /**
     * Writes what the checkout sheet reviewed.
     *
     * The sheet knows more than a plain trip does: every row carries its own price box, its own account
     * and its own category, and the trip's name is what the resulting ledger rows are called.
     * The rules live in [buildDraftLines] so they can be unit tested without Android.
     */
    internal suspend fun postTripDrafts(
        items: List<ShoppingItemEntity>,
        drafts: List<CheckoutDraft>,
        priceText: Map<UUID, String>,
        tripName: String,
        fallbackAccountId: UUID? = null,
        fallbackCategoryId: UUID? = null,
    ): Int = postLines(
        lines = buildDraftLines(
            drafts = drafts,
            priceText = priceText,
            tripName = tripName,
            fallbackAccountId = fallbackAccountId,
            fallbackCategoryId = fallbackCategoryId,
        ),
        items = items,
    )

    /**
     * Posts the whole trip as one expense.
     *
     * Returns how many ledger rows were written: 1 when the trip was posted, 0 when there was nothing
     * to post (no account, no price, or every item already posted). The items are re-read from the
     * database first, so two taps in quick succession cannot pay for the same item twice: the second
     * call sees the stamps written by the first one and posts nothing.
     */
    internal suspend fun postTripAsOneTransaction(
        drafts: List<CheckoutDraft>,
        priceText: Map<UUID, String>,
        tripName: String,
        accountId: UUID?,
        categoryId: UUID?,
    ): Int {
        // The amount is the rule the sheet showed and the rule the tests pin down.
        val line = buildSingleTransactionLine(
            drafts = drafts,
            priceText = priceText,
            tripName = tripName,
            accountId = accountId,
            categoryId = categoryId,
        ) ?: return 0

        val account = accountRepository.findById(AccountId(line.accountId)) ?: return 0
        val amount = PositiveDouble.from(line.amount).getOrNull() ?: return 0

        // Re-read: an item that already points at a transaction is never paid for twice.
        val contributors = drafts.mapNotNull { draft ->
            val price = parsePriceInput(priceText[draft.itemId].orEmpty()) ?: return@mapNotNull null
            val item = shoppingItemDao.findById(draft.itemId) ?: return@mapNotNull null
            if (item.convertedTransactionId != null) return@mapNotNull null
            item to price
        }
        if (contributors.isEmpty()) return 0

        val expense = Expense(
            id = TransactionId(UUID.randomUUID()),
            title = NotBlankTrimmedString.from(line.title).getOrNull(),
            // The breakdown lives in the shopping items, not in a description nobody can parse.
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

        for ((item, price) in contributors) {
            writeShoppingItemDao.save(
                item.copy(
                    // Remember what was really paid so next week's trip doesn't ask again.
                    unitPrice = price,
                    convertedTransactionId = expense.id.value,
                    checkedAt = item.checkedAt ?: Instant.now(),
                )
            )
        }
        return 1
    }

    /**
     * The single place where a ledger line is actually written for the per-item shape: a row titled
     * with the trip's name and described with the item's name.
     */
    private suspend fun postLines(
        lines: List<CheckoutLine>,
        items: List<ShoppingItemEntity>,
    ): Int {
        val itemsById = items.associateBy { it.id }
        var posted = 0
        for (line in lines) {
            val account = accountRepository.findById(AccountId(line.accountId)) ?: continue
            val amount = PositiveDouble.from(line.amount).getOrNull() ?: continue

            val expense = Expense(
                id = TransactionId(UUID.randomUUID()),
                title = NotBlankTrimmedString.from(line.title).getOrNull(),
                description = line.description
                    ?.let { NotBlankTrimmedString.from(it).getOrNull() },
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

            val item = itemsById[line.itemId] ?: continue
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
