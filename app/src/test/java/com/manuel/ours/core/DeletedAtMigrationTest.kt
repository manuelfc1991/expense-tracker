package com.manuel.ours.core

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.db.AppDatabase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 7 → 8 migration, run against a v7 table with rows already in it.
 *
 * This one is worth a test of its own rather than trusting the schema diff. The
 * household's database is the only copy of six months of bank history; the app carries
 * **no destructive fallback** precisely so a bad migration crashes instead of wiping, and
 * a crash on open is still the app being unusable until a new build reaches the phone.
 *
 * The specific thing being pinned is the null. Trash is a window over `deletedAt`, and
 * the first real phone had 446 tombstones from dedupe repairs rather than from a person
 * deleting anything. If this migration ever backfilled a timestamp — `DEFAULT
 * (strftime('%s','now'))` is the tempting one-liner — Trash would open on 446 entries
 * nobody threw away, and the one row somebody wanted back would be buried.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application, not OursApp. Booting the real one builds the Hilt graph, which
// opens the SQLCipher database and loads a native library that does not exist on the JVM
// — the failure looks like a migration fault and is nothing of the sort.
@Config(sdk = [33], application = android.app.Application::class)
class DeletedAtMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The columns of `transactions` as they stood at schema 7. */
    private val v7 = """
        CREATE TABLE transactions (
            id TEXT NOT NULL PRIMARY KEY,
            amountPaise INTEGER NOT NULL,
            type TEXT NOT NULL,
            merchant TEXT NOT NULL,
            category TEXT NOT NULL,
            occurredAt INTEGER NOT NULL,
            accountTail TEXT,
            refNo TEXT,
            bank TEXT,
            note TEXT,
            splitType TEXT NOT NULL,
            source TEXT NOT NULL,
            ownerUid TEXT NOT NULL,
            ownerName TEXT NOT NULL,
            needsReview INTEGER NOT NULL,
            rawSms TEXT,
            deleted INTEGER NOT NULL,
            deleteRequestedBy TEXT,
            amountEditedAt INTEGER,
            counterpartyTail TEXT,
            balancePaise INTEGER,
            dedupeKey TEXT NOT NULL,
            dedupeAt INTEGER NOT NULL,
            updatedAtLamport INTEGER NOT NULL,
            updatedByDevice TEXT NOT NULL
        )
    """.trimIndent()

    private fun openV7(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(v7)
            }

            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(callback)
                .build()
        )
    }

    private fun insertRow(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        deleted: Int,
    ) {
        db.execSQL(
            """
            INSERT INTO transactions VALUES
            ('$id', 45075, 'DEBIT', 'Chai stall', 'Food', 1000, '3062', NULL, 'KGBANK',
             NULL, 'SHARED', 'SMS', 'uid', 'Manuel', 0, 'raw', $deleted, NULL, NULL,
             NULL, NULL, 'k-$id', 1000, 1, 'phone-a')
            """.trimIndent()
        )
    }

    @Test
    fun `the column arrives null on every existing row, deleted or not`() {
        val helper = openV7()
        val db = helper.writableDatabase
        insertRow(db, "live", deleted = 0)
        insertRow(db, "tombstone", deleted = 1)

        AppDatabase.MIGRATION_7_8.migrate(db)

        db.query("SELECT id, deleted, deletedAt FROM transactions ORDER BY id").use { c ->
            assertThat(c.count).isEqualTo(2)
            while (c.moveToNext()) {
                // The null is the whole point: an old tombstone must read as older than
                // the window, not as something deleted at upgrade time.
                assertThat(c.isNull(2)).isTrue()
            }
        }
        helper.close()
    }

    @Test
    fun `nothing is lost or rewritten on the way through`() {
        val helper = openV7()
        val db = helper.writableDatabase
        insertRow(db, "keep", deleted = 0)

        AppDatabase.MIGRATION_7_8.migrate(db)

        db.query("SELECT amountPaise, merchant, category, rawSms, dedupeKey FROM transactions")
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(45075)
                assertThat(c.getString(1)).isEqualTo("Chai stall")
                assertThat(c.getString(2)).isEqualTo("Food")
                assertThat(c.getString(3)).isEqualTo("raw")
                assertThat(c.getString(4)).isEqualTo("k-keep")
            }
        helper.close()
    }

    @Test
    fun `the new column accepts a stamp afterwards`() {
        val helper = openV7()
        val db = helper.writableDatabase
        insertRow(db, "a", deleted = 0)

        AppDatabase.MIGRATION_7_8.migrate(db)
        db.execSQL("UPDATE transactions SET deleted = 1, deletedAt = 1785000000000 WHERE id = 'a'")

        db.query("SELECT deletedAt FROM transactions WHERE id = 'a'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1785000000000L)
        }
        helper.close()
    }
}
