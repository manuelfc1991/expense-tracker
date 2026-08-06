package com.manuel.ours.sms

import com.manuel.ours.data.sms.SmsParser
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real parser over a real inbox dump and prints what it makes of it.
 *
 * Not a guard — an instrument. It is skipped unless the dump is present, so it never
 * runs on a machine that does not have one. The dump is a household's messages and is
 * deliberately not committed.
 *
 *   adb shell content query --uri content://sms/inbox --projection address:body > dump.txt
 */
class RealInboxAuditTest {

    private val dump = File(
        System.getProperty("ours.inbox.dump")
            ?: System.getenv("OURS_INBOX_DUMP")
            ?: "/nonexistent"
    )

    @Test
    fun `report what the parser makes of the real inbox`() {
        assumeTrue("no inbox dump supplied", dump.isFile)

        val text = dump.readText()
        val messages = Regex("Row: \\d+ address=(.*?), body=", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .toList()
            .mapIndexed { i, m ->
                val start = m.range.last + 1
                val end = if (i + 1 < text.length) {
                    Regex("\nRow: \\d+ address=").find(text, start)?.range?.first ?: text.length
                } else text.length
                m.groupValues[1] to text.substring(start, end).trim()
            }

        val parser = SmsParser()
        var expense = 0
        var reminder = 0
        val ignored = LinkedHashMap<String, Int>()
        val byBank = LinkedHashMap<String, Int>()
        var spentPaise = 0L

        messages.forEach { (sender, body) ->
            when (val r = parser.parse(sender, body, 0L)) {
                is SmsParser.Result.Expense -> {
                    expense++
                    byBank[r.txn.bank] = (byBank[r.txn.bank] ?: 0) + 1
                    if (r.txn.type.name == "DEBIT" && r.txn.kind != SmsParser.Kind.CARD_BILL_PAYMENT) {
                        spentPaise += r.txn.amountPaise
                    }
                }
                is SmsParser.Result.BillReminder -> reminder++
                is SmsParser.Result.Ignored ->
                    ignored[r.reason.name] = (ignored[r.reason.name] ?: 0) + 1
            }
        }

        println("=== messages: ${messages.size}")
        println("=== expenses: $expense   reminders: $reminder")
        println("=== debits (excl. card bills): ${"%,.2f".format(spentPaise / 100.0)}")
        println("--- AUGUST 2026, every debit the app would count as spending")
        val augStart = 1_785_522_600_000L
        val augEnd = augStart + 31L * 24 * 3_600_000L
        var aug = 0L
        messages.forEach { (sender, body) ->
            val r = parser.parse(sender, body, 0L)
            if (r is SmsParser.Result.Expense &&
                r.txn.occurredAt in augStart until augEnd &&
                r.txn.type.name == "DEBIT"
            ) {
                aug += r.txn.amountPaise
                println("    %10s  %-28s %s".format(
                    "%,.2f".format(r.txn.amountPaise / 100.0),
                    r.txn.merchant?.take(28) ?: "(no payee)",
                    r.txn.kind,
                ))
            }
        }
        println("=== August debits total: ${"%,.2f".format(aug / 100.0)}")
        println("--- by bank")
        byBank.entries.sortedByDescending { it.value }.forEach { println("    %5d  %s".format(it.value, it.key)) }
        println("--- ignored")
        ignored.entries.sortedByDescending { it.value }.forEach { println("    %5d  %s".format(it.value, it.key)) }
    }
}
