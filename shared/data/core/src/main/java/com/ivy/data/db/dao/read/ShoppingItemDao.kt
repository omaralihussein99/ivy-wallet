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
}
