package com.manuel.ours.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        SyncEventEntity::class,
        PeerCursorEntity::class,
        MerchantRuleEntity::class,
        BudgetEntity::class,
        MemberEntity::class,
        ReminderEntity::class,
        SharedRuleEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sharedRuleDao(): SharedRuleDao
    abstract fun transactionDao(): TransactionDao
    abstract fun syncEventDao(): SyncEventDao
    abstract fun peerCursorDao(): PeerCursorDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun budgetDao(): BudgetDao
    abstract fun memberDao(): MemberDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        /**
         * Deliberately a different filename from the old plaintext `ours.db`, so an
         * upgrade can delete the legacy file instead of SQLCipher failing to open it.
         */
        const val NAME = "ours-secure.db"
        const val OVERALL_BUDGET_KEY = "__OVERALL__"

        /**
         * Adds `shared_rules`. Written by hand rather than left to a destructive
         * fallback, because the fallback drops every table — on the first real
         * household that is six months of bank history that exists nowhere else on
         * the phone.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shared_rules (
                        type TEXT NOT NULL,
                        ruleKey TEXT NOT NULL,
                        value TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deviceId TEXT NOT NULL,
                        PRIMARY KEY(type, ruleKey)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
