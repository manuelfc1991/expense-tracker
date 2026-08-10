package com.manuel.ours.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.db.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * That a migration leaves the table Room is about to demand.
 *
 * Room compares the live schema against the exported JSON every time the database is opened,
 * and a disagreement is not a warning — it throws. On this app that means a crash on launch
 * against the only copy of the household's ledger, on a phone that must never be reinstalled.
 * The failure mode is also invisible until then: a migration with the wrong column type, a
 * stray `NOT NULL`, or a `DEFAULT` the entity does not declare all build cleanly, all pass
 * every other test, and all take the app down on the first start after the update.
 *
 * Nothing tested migrations before this. The `room-testing` dependency has been declared since
 * the project began and nothing ever imported it, across eleven schema versions — so the eleven
 * migrations already shipped are covered by nothing except having happened to work.
 *
 * This checks the column shape rather than Room's identity hash, because the shape is the part
 * a person writes by hand and therefore the part that goes wrong. Both the starting table and
 * the expected result are read out of the exported schemas, so the test cannot drift from what
 * Room will actually ask for: change the entity without changing the migration and it fails.
 *
 * The migration is driven through a real [SupportSQLiteOpenHelper] upgrade rather than called
 * directly, which is both how it runs on the phone and the only way to hand it a
 * `SupportSQLiteDatabase` — the framework implementation of that interface is internal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TransactionsMigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun schema(version: Int): File =
        File("schemas/com.manuel.ours.data.db.AppDatabase/$version.json")

    private fun transactionsEntity(version: Int) =
        json.parseToJsonElement(schema(version).readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == "transactions" }

    /** The `CREATE TABLE` Room exported for one version, ready to execute. */
    private fun createSql(version: Int): String =
        transactionsEntity(version)["createSql"]!!.jsonPrimitive.content
            .replace("\${TABLE_NAME}", "transactions")

    /** Column name to (declared type, notNull, default) as the exported schema states it. */
    private fun expectedColumns(version: Int): Map<String, Triple<String, Boolean, String?>> =
        transactionsEntity(version)["fields"]!!.jsonArray.associate { field ->
            val f = field.jsonObject
            f["columnName"]!!.jsonPrimitive.content to Triple(
                f["affinity"]!!.jsonPrimitive.content,
                f["notNull"]!!.jsonPrimitive.content.toBoolean(),
                f["defaultValue"]?.jsonPrimitive?.content,
            )
        }

    private fun actualColumns(db: SupportSQLiteDatabase): Map<String, Triple<String, Boolean, String?>> {
        val out = mutableMapOf<String, Triple<String, Boolean, String?>>()
        db.query("PRAGMA table_info(transactions)").use { c ->
            while (c.moveToNext()) {
                out[c.getString(c.getColumnIndexOrThrow("name"))] = Triple(
                    c.getString(c.getColumnIndexOrThrow("type")),
                    c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                    c.getString(c.getColumnIndexOrThrow("dflt_value")),
                )
            }
        }
        return out
    }

    /**
     * Lays down a v11 `transactions` table, optionally with a row in it, then reopens at v12 so
     * the helper runs [AppDatabase.MIGRATION_11_12] as an upgrade.
     */
    private fun migrateFromV11(seed: (SQLiteDatabase) -> Unit = {}): SupportSQLiteOpenHelper {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "migration-11-12.db")
        file.delete()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            raw.execSQL(createSql(11))
            seed(raw)
            raw.version = 11
        }

        val callback = object : SupportSQLiteOpenHelper.Callback(12) {
            override fun onCreate(db: SupportSQLiteDatabase) =
                error("The database was supposed to exist at v11 already")

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                assertThat(oldVersion).isEqualTo(11)
                assertThat(newVersion).isEqualTo(12)
                AppDatabase.MIGRATION_11_12.migrate(db)
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(callback)
                .build()
        )
    }

    @Test
    fun `the exported schemas this test reads are actually there`() {
        // A guard on the harness, not on the app. These are read from disk by relative path, so
        // a change to the test working directory would otherwise turn every assertion below
        // into a silent pass over an empty map.
        assertThat(schema(11).exists()).isTrue()
        assertThat(schema(12).exists()).isTrue()
        assertThat(expectedColumns(11)).doesNotContainKey("transferPeerId")
        assertThat(expectedColumns(12)).containsKey("transferPeerId")
    }

    /**
     * The migration itself: afterwards the table is exactly what v12 declares.
     *
     * Every column, not only the new one — an `ALTER TABLE` that ran against the wrong table, or
     * a migration that dropped and rebuilt, shows up here and nowhere else.
     */
    @Test
    fun `migrating 11 to 12 produces the table Room expects`() {
        migrateFromV11().use { helper ->
            assertThat(actualColumns(helper.writableDatabase)).isEqualTo(expectedColumns(12))
        }
    }

    /**
     * And it leaves the rows already there alone.
     *
     * The one rule of this project is that the ledger survives. A migration that quietly reset a
     * column would be indistinguishable from a working one until somebody noticed a total had
     * moved, by which time the previous value is gone.
     */
    @Test
    fun `rows already in the ledger keep their values and gain a null`() {
        val helper = migrateFromV11 { raw ->
            raw.execSQL(
                """
                INSERT INTO transactions
                  (id, amountPaise, type, merchant, category, occurredAt, accountTail, refNo,
                   bank, note, splitType, source, ownerUid, ownerName, needsReview, rawSms,
                   deleted, refundedPaise, dedupeKey, dedupeAt, updatedAtLamport, updatedByDevice)
                VALUES
                  ('t1', 46841, 'DEBIT', 'ICICI', 'SELF_TRANSFER', 100, '3008', NULL,
                   'ICICI Bank', NULL, 'SHARED', 'SMS', 'manuel', 'Manuel', 0, NULL,
                   0, 0, 'k1', 100, 1, 'dev')
                """.trimIndent()
            )
        }
        helper.use {
            it.writableDatabase.query(
                "SELECT amountPaise, category, transferPeerId FROM transactions WHERE id = 't1'"
            ).use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(46841L)
                assertThat(c.getString(1)).isEqualTo("SELF_TRANSFER")
                // Null, and deliberately not backfilled: the self-transfers already in the
                // ledger were paired by matching amounts, which is an inference, not the
                // stated move this column records.
                assertThat(c.isNull(2)).isTrue()
            }
        }
    }
}
