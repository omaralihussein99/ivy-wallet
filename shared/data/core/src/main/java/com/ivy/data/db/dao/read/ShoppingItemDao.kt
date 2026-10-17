package com.ivy.data.db.dao.read

import androidx.room.Dao
import androidx.room.Query
import com.ivy.data.db.entity.ShoppingItemEntity
import java.util.UUID

@Dao
interface ShoppingItemDao {
    @Query("SELECT * FROM shopping_items WHERE listId = :listId AND isDeleted = 0 ORDER BY orderNum ASC")
    suspend fun findByListId(listId: UUID): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE id = :id")
    suspend fun findById(id: UUID): ShoppingItemEntity?

    @Query("SELECT COUNT(*) FROM shopping_items WHERE listId = :listId AND isChecked = 1 AND isDeleted = 0")
    suspend fun checkedCount(listId: UUID): Int

    @Query("SELECT COUNT(*) FROM shopping_items WHERE listId = :listId AND isDeleted = 0")
    suspend fun totalCount(listId: UUID): Int

    /**
     * The items that a ledger transaction was made of.
     *
     * A shopping trip posts either one row per item (each item points at its own transaction) or a
     * single row for the whole trip (every item of the trip points at that same transaction). In
     * both cases this is the only lookup needed to show the breakdown of a transaction, which is
     * why posting a trip as one transaction needs no new column.
     */
    @Query(
        "SELECT * FROM shopping_items WHERE convertedTransactionId = :transactionId " +
            "AND isDeleted = 0 ORDER BY orderNum ASC"
    )
    suspend fun findByConvertedTransactionId(transactionId: UUID): List<ShoppingItemEntity>

    /**
     * Every item that really reached the ledger, i.e. that carries the transaction id stamped when
     * its trip was posted.
     *
     * This is the source of the "last paid" hint. An item that was only planned, skipped as unpriced
     * or still sits on an open trip has no stamp, so it is not a purchase and must not be treated as
     * one. No new column is needed: the stamp already exists.
     */
    @Query(
        "SELECT * FROM shopping_items WHERE convertedTransactionId IS NOT NULL " +
            "AND isDeleted = 0 ORDER BY orderNum ASC"
    )
    suspend fun findAllPosted(): List<ShoppingItemEntity>
}
