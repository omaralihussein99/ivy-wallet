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

    /**
     * The reusable originals. A template is the list you buy most weeks, with items and no state.
     */
    @Query("SELECT * FROM shopping_lists WHERE isDeleted = 0 AND isTemplate = 1 ORDER BY orderNum ASC")
    suspend fun findTemplates(): List<ShoppingListEntity>

    /**
     * Trips: the working copies you tick off in the shop. Rows created before templates existed have
     * isTemplate as NULL, and those are trips.
     */
    @Query(
        "SELECT * FROM shopping_lists WHERE isDeleted = 0 AND isArchived = 0 " +
            "AND (isTemplate IS NULL OR isTemplate = 0) ORDER BY orderNum ASC"
    )
    suspend fun findTrips(): List<ShoppingListEntity>
}
