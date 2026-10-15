package com.ivy.data.db.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ivy.base.kotlinxserilzation.KSerializerInstant
import com.ivy.base.kotlinxserilzation.KSerializerUUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * One line of a shopping list.
 *
 * [unitPrice] is the planned price per unit and may be unknown, in which case it is null
 * and the estimate simply excludes that line.
 *
 * [convertedTransactionId] is the double posting guard: once a checked item has been added
 * to the ledger, this points at the resulting transaction and the item can never be posted
 * a second time.
 */
@Suppress("DataClassDefaultValues")
@Keep
@Serializable
@Entity(tableName = "shopping_items")
data class ShoppingItemEntity(
    @PrimaryKey
    @SerialName("id")
    @Serializable(with = KSerializerUUID::class)
    val id: UUID = UUID.randomUUID(),

    @SerialName("listId")
    @Serializable(with = KSerializerUUID::class)
    val listId: UUID,

    @SerialName("name")
    val name: String,
    @SerialName("quantity")
    val quantity: Double = 1.0,
    @SerialName("unit")
    val unit: String? = null,

    @SerialName("unitPrice")
    val unitPrice: Double? = null,

    @SerialName("isChecked")
    val isChecked: Boolean = false,

    /**
     * When the item was actually ticked, not when the row was created. Cleared again when the item
     * is unticked, so it can never claim something was bought that is still on the list.
     */
    @SerialName("checkedAt")
    @Serializable(with = KSerializerInstant::class)
    val checkedAt: Instant? = null,

    @SerialName("categoryId")
    @Serializable(with = KSerializerUUID::class)
    val categoryId: UUID? = null,
    @SerialName("note")
    val note: String? = null,

    @SerialName("convertedTransactionId")
    @Serializable(with = KSerializerUUID::class)
    val convertedTransactionId: UUID? = null,

    @SerialName("orderNum")
    val orderNum: Double = 0.0,

    @SerialName("dateTime")
    @Serializable(with = KSerializerInstant::class)
    val dateTime: Instant = Instant.now(),

    @Deprecated("Obsolete field used for cloud sync. Can't be deleted because of backwards compatibility")
    @SerialName("isDeleted")
    val isDeleted: Boolean = false,

    @Deprecated("Obsolete field used for cloud sync. Can't be deleted because of backwards compatibility")
    @SerialName("lastSyncedTime")
    @Serializable(with = KSerializerInstant::class)
    val lastSyncedTime: Instant = Instant.ofEpochMilli(0)
)
