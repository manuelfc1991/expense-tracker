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
    version = 10,
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
        /**
         * Adds the delete-request marker. A nullable column with no default, so every
         * existing row simply has no pending request — nothing to backfill and nothing
         * that can change a total on upgrade.
         */
        /** Adds the hand-edit stamp. Nullable, so every existing row is untouched. */
        /** Adds the destination-account identifier. Nullable; nothing to backfill. */
        /**
         * Adds the bank's own closing balance. Nullable with no default, so every row
         * already in the ledger simply has none — and it backfills itself: the next
         * message from an account carries that account's balance, so the figure appears
         * as the household uses the app rather than needing a rescan.
         */
        /**
         * Adds the deletion stamp that Trash is a window over. Nullable with no
         * default, deliberately: every tombstone already in the ledger keeps a null and
         * is therefore treated as older than the window. See TransactionEntity.deletedAt
         * — backfilling these would have opened Trash on hundreds of rows that dedupe
         * repairs deleted, not a person.
         */
        /**
         * Adds the two halves of a refund link.
         *
         * Nullable and zero-defaulted, so every row already in the ledger is untouched and reads
         * as "not a refund and not refunded" — which is what it is. Nothing is backfilled: a
         * refund is a claim about two rows that only a person can make, and guessing them from
         * matching amounts is the trap this feature exists to avoid.
         *
         * Two columns rather than one on purpose. Each row then says what it is without a join,
         * which matters twice: the list draws a struck-through amount without looking anything
         * up, and sync is last-write-wins **per row**, so a link that lived only on the credit
         * would leave the debit on the other phone still counted as spending.
         */
        /**
         * The bank's own message id, so one debit reported twice is one row.
         *
         * Kerala Gramin sends a detailed SMS and a bare one for the same payment. Only the
         * detailed one carries a UPI reference, and the pair arrives just over three minutes
         * apart — outside the dedup window — so both were stored. On this ledger that was a
         * 1,778 card bill and a 7,177.79 one, 8,955.79 counted twice in a single month.
         *
         * Nullable with no default: rows written before this migration genuinely have no
         * message id, and NULL is the honest way to say so. Two NULLs must never match, which
         * is why the comparison in SmsDeduplicator requires both sides to be present.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN bankMessageId TEXT")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN refundsTxnId TEXT")
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN refundedPaise INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN deletedAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN balancePaise INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN counterpartyTail TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN amountEditedAt INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN deleteRequestedBy TEXT")
            }
        }

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
