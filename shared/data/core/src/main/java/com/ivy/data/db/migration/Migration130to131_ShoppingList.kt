package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Suppress("MagicNumber", "ClassNaming")
class Migration130to131_ShoppingList : Migration(130, 131) {
    companion object {
        private const val SHOPPING_LISTS_TABLE = "shopping_lists"
        private const val SHOPPING_ITEMS_TABLE = "shopping_items"
    }

    @Suppress("MaximumLineLength", "MaxLineLength")
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `${SHOPPING_LISTS_TABLE}` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `color` INTEGER NOT NULL, `icon` TEXT, `orderNum` REAL NOT NULL, `dateTime` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `lastSyncedTime` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )

        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `${SHOPPING_ITEMS_TABLE}` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `name` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit` TEXT, `unitPrice` REAL, `isChecked` INTEGER NOT NULL, `categoryId` TEXT, `note` TEXT, `convertedTransactionId` TEXT, `orderNum` REAL NOT NULL, `dateTime` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `lastSyncedTime` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}
