package com.ivy.data.db.dao.read

import androidx.room.Dao
import androidx.room.Query
import com.ivy.data.db.entity.ShoppingListEntity
import java.util.UUID

@Dao
interface ShoppingListDao {
    @Query("SELECT * FROM shopping_lists WHERE isDeleted = 0 AND isArchived = 0 ORDER BY orderNum ASC")
    suspend fun findAll(): List<ShoppingListEntity>

    @Query("SELECT * FROM shopping_lists WHERE isDeleted = 0 ORDER BY orderNum ASC")
    suspend fun findAllIncludingArchived(): List<ShoppingListEntity>

    @Query("SELECT * FROM shopping_lists WHERE id = :id")
    suspend fun findById(id: UUID): ShoppingListEntity?

    @Query("SELECT * FROM shopping_lists WHERE name LIKE '%' || :text || '%' AND isDeleted = 0")
    suspend fun findByText(text: String): List<ShoppingListEntity>
}
