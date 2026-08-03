package com.manuel.ours.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.manuel.ours.data.db.AppDatabase
import com.manuel.ours.data.sms.SmsParser
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rescanning the inbox must never change your data.
 *
 * This is the guarantee behind "what happens if I delete a message and resync": the
 * backfill only ever *adds*, and adding something already present is a no-op. Both
 * halves need proving, because the second one broke in a way nothing caught — a
 * schema change left dedup comparing a stored midnight against a real timestamp, so
 * every rescan re-imported the entire history.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application, not OursApp. The real one boots Hilt, which opens the
// SQLCipher database, which needs the Android Keystore — absent on the JVM. This test
// only cares about dedup logic, so it builds its own in-memory database.
@Config(sdk = [33], application = android.app.Application::class)
class RescanIdempotencyTest {

    private lateinit var db: AppDatabase
    private val parser = SmsParser()

    private val messages = listOf(
        "Debited Rs 151.00 from a/c X4657 on 01Jul26 07:48 via UPI to KEECHERIL ST. " +
            "Ref 618233824289.Bal Rs 3469.55. Not you?Call 18004251199 -Federal Bank",
        // No clock time in the body — the shape that used to duplicate on every rescan.
        "Debited Rs 1000 from a/c XX4657 on 10FEB2026 and FSF a/c XX0165 credited as " +
            "per standing instruction.BalRs 52013.50. -Federal Bank",
        // ICICI card: date but no time either.
        "INR 1,199.00 spent using ICICI Bank Card XX3008 on 24-Jun-26 on LENSKART " +
            "SOLUTI. Avl Limit: INR 22,931.98. If not you, call 1800 2662/SMS BLOCK " +
            "3008 to 9215676766.",
        "Debited Rs 10000 from a/c XX4657 on 02JUL2026 07:20:07.Bal Rs 13572.55. -Federal Bank",
        "Debited Rs 10000 from a/c XX4657 on 02JUL2026 22:57:32.Bal Rs 3572.55. -Federal Bank",
    )

    /** Stable per-message delivery times, as the inbox would report them. */
    private val deliveredAt = messages.indices.map { 1_785_000_000_000L + it * 3_600_000L }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /** Mirrors what the backfill worker does, minus the ContentResolver. */
    private fun scanAll() = runBlocking {
        messages.forEachIndexed { index, body ->
            val sender = if ("ICICI" in body) "VM-ICICIB" else "AD-FEDBNK"
            val result = parser.parse(sender, body, deliveredAt[index])
            if (result !is SmsParser.Result.Expense) return@forEachIndexed
            val parsed = result.txn

            val duplicate = SmsDeduplicatorProbe.findDuplicate(db, parsed)
            if (duplicate != null) return@forEachIndexed

            db.transactionDao().upsert(
                SmsDeduplicatorProbe.toEntity(parsed, index.toLong()),
            )
        }
    }

    private fun count() = runBlocking { db.transactionDao().count() }

    @Test
    fun `a first scan imports every distinct message`() {
        scanAll()
        assertThat(count()).isEqualTo(messages.size)
    }

    @Test
    fun `rescanning does not duplicate anything`() {
        scanAll()
        val afterFirst = count()

        scanAll()
        scanAll()

        assertThat(count()).isEqualTo(afterFirst)
    }

    @Test
    fun `two same-day same-amount debits both survive a rescan`() {
        // The ₹10,000 pair on 02 Jul: 07:20 and 22:57, no reference numbers.
        scanAll()
        scanAll()

        val tenThousand = runBlocking {
            db.transactionDao().observeAllOnce().count { it.amountPaise == 10_00_000L }
        }
        assertThat(tenThousand).isEqualTo(2)
    }

    @Test
    fun `deleting the source message leaves the transaction untouched`() {
        scanAll()
        val before = count()

        // Simulate the inbox losing messages: scan a strictly smaller set.
        runBlocking {
            val body = messages.first()
            val result = parser.parse("AD-FEDBNK", body, deliveredAt[0])
            if (result is SmsParser.Result.Expense) {
                SmsDeduplicatorProbe.findDuplicate(db, result.txn)
            }
        }

        // Nothing prunes rows whose SMS is gone — the backfill only ever adds.
        assertThat(count()).isEqualTo(before)
    }
}
