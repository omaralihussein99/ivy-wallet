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
 * A shopping list. Purely a planning aid: it never posts anything to the ledger
 * by itself. Adding items to the budget is an explicit user action on the screen.
 */
@Suppress("DataClassDefaultValues")
@Keep
@Serializable
@Entity(tableName = "shopping_lists")
data class ShoppingListEntity(
    @PrimaryKey
    @SerialName("id")
    @Serializable(with = KSerializerUUID::class)
    val id: UUID = UUID.randomUUID(),

    @SerialName("name")
    val name: String,

    /**
     * The account that will pay for this list. Null means the user has not decided yet, and the
     * post-to-ledger action has to ask instead of guessing.
     */
    @SerialName("accountId")
    @Serializable(with = KSerializerUUID::class)
    val accountId: UUID? = null,

    /**
     * Currency the estimate is expressed in. Null means "follow the paying account, falling back
     * to the app base currency when there is none".
     */
    @SerialName("currency")
    val currency: String? = null,

    @SerialName("description")
    val description: String? = null,

    @SerialName("color")
    val color: Int,
    @SerialName("icon")
    val icon: String? = null,
    @SerialName("orderNum")
    val orderNum: Double = 0.0,

    @SerialName("dateTime")
    @Serializable(with = KSerializerInstant::class)
    val dateTime: Instant = Instant.now(),

    @SerialName("isArchived")
    val isArchived: Boolean = false,

    @Deprecated("Obsolete field used for cloud sync. Can't be deleted because of backwards compatibility")
    @SerialName("isDeleted")
    val isDeleted: Boolean = false,

    @Deprecated("Obsolete field used for cloud sync. Can't be deleted because of backwards compatibility")
    @SerialName("lastSyncedTime")
    @Serializable(with = KSerializerInstant::class)
    val lastSyncedTime: Instant = Instant.ofEpochMilli(0)
)
