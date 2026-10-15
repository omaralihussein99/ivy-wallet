package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ivy.data.db.entity.ShoppingListEntity
import java.util.UUID

@Dao
interface WriteShoppingListDao {
    @Upsert
    suspend fun save(value: ShoppingListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: List<ShoppingListEntity>)

    @Query("DELETE FROM shopping_lists WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM shopping_lists")
    suspend fun deleteAll()
}
