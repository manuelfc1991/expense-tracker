package com.manuel.ours.sms

import com.manuel.ours.data.sms.SmsParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Bill reminders were parsed correctly for weeks and then thrown away — nothing ever
 * wrote them to the database. These cover the half that was missing: pulling a usable
 * due date out of the text, so a reminder can actually remind.
 */
class BillReminderTest {

    private val parser = SmsParser()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = 1_785_000_000_000L

    private fun reminder(sender: String, body: String): SmsParser.Result.BillReminder {
        val result = parser.parse(sender, body, now)
        assertThat(result).isInstanceOf(SmsParser.Result.BillReminder::class.java)
        return result as SmsParser.Result.BillReminder
    }

    private fun dayOf(epochMillis: Long) =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    @Test
    fun `card statement yields amount and due date`() {
        val r = reminder(
            "VM-ICICIB",
            "ICICI Bank Credit Card XX3008 Statement is sent to ma********ya@gmail.com. " +
                "Total of Rs 729.50 or minimum of Rs 100.00 is due by 30-APR-26.",
        )
        assertThat(r.amountPaise).isEqualTo(72_950)
        assertThat(dayOf(r.dueAt!!).dayOfMonth).isEqualTo(30)
        assertThat(dayOf(r.dueAt!!).monthValue).isEqualTo(4)
    }

    @Test
    fun `due on is understood as well as due by`() {
        val r = reminder(
            "VM-ICICIB",
            "Total amount due Rs.12,450.00 on your card XX9012 is due on 15-07-26.",
        )
        assertThat(dayOf(r.dueAt!!).dayOfMonth).isEqualTo(15)
    }

    @Test
    fun `the due date wins over the issue date in the same message`() {
        // Both dates are present. Anchoring on the first one found would make a bill
        // issued on the 3rd look overdue the moment it arrives.
        val r = reminder(
            "AD-HDFCBK",
            "Your BESCOM bill of Rs.2,340 generated on 03-JUL-26 is due on 20-JUL-26.",
        )
        assertThat(dayOf(r.dueAt!!).dayOfMonth).isEqualTo(20)
    }

    @Test
    fun `a reminder with no date has a null due date rather than a wrong one`() {
        val r = reminder(
            "VM-ICICIB",
            "Total amount due on your card XX9012 is Rs.5,000.00. Please pay by the due date.",
        )
        assertThat(r.dueAt).isNull()
    }

    @Test
    fun `a paid bill is still an expense, not a reminder`() {
        val result = parser.parse(
            "AD-HDFCBK",
            "Rs.2,340 debited from A/c XX1234 towards BESCOM bill payment on 18-07-26",
            now,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
    }

    @Test
    fun `reminders never become transactions`() {
        // The whole point of the separation: an unpaid bill must not enter the
        // spending total, or paying it later double-counts.
        val bodies = listOf(
            "Total of Rs 729.50 or minimum of Rs 100.00 is due by 30-APR-26.",
            "Your bill of Rs.1,200 is due on 12-08-26.",
            "Minimum amount due Rs.500 due date 25-08-26",
        )
        bodies.forEach {
            assertThat(parser.parse("VM-ICICIB", it, now))
                .isNotInstanceOf(SmsParser.Result.Expense::class.java)
        }
    }
}
