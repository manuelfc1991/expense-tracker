package com.manuel.ours.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionEntity::class,
        SyncEventEntity::class,
        PeerCursorEntity::class,
        MerchantRuleEntity::class,
        BudgetEntity::class,
        MemberEntity::class,
        ReminderEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
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
    }
}
