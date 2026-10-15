package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * One migration covering the whole feature batch, so there is a single schema change to verify:
 *
 *  - a shopping list remembers which account pays for it, and in which currency the estimate is
 *    expressed, so an estimate can never be silently shown in the wrong unit,
 *  - a shopping item remembers when it was ticked, so "bought on Tuesday, posted on Friday" is
 *    still readable afterwards,
 *  - an account can have a parent, which is what account hierarchy is built on.
 *
 * All four columns are nullable, so existing rows keep working untouched.
 */
class Migration131to132 : Migration(131, 132) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE shopping_lists ADD COLUMN accountId TEXT")
        db.execSQL("ALTER TABLE shopping_lists ADD COLUMN currency TEXT")
        // Nullable on purpose rather than NOT NULL DEFAULT 0: Room only accepts a column default
        // if the entity declares @ColumnInfo(defaultValue = ...), and an undeclared default is a
        // schema mismatch that fails at runtime. Null simply means "a trip, not a template".
        db.execSQL("ALTER TABLE shopping_lists ADD COLUMN isTemplate INTEGER")
        db.execSQL("ALTER TABLE shopping_items ADD COLUMN checkedAt INTEGER")
        db.execSQL("ALTER TABLE accounts ADD COLUMN parentAccountId TEXT")
    }
}
