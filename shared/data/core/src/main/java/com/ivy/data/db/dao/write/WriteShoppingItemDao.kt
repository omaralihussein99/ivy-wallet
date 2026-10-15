package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ivy.data.db.entity.ShoppingItemEntity
import java.util.UUID

@Dao
interface WriteShoppingItemDao {
    @Upsert
    suspend fun save(value: ShoppingItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: List<ShoppingItemEntity>)

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM shopping_items WHERE listId = :listId")
    suspend fun deleteByListId(listId: UUID)

    @Query("DELETE FROM shopping_items")
    suspend fun deleteAll()
}
