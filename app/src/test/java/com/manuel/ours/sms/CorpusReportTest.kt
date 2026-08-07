package com.manuel.ours.sms

import com.manuel.ours.core.Money
import com.manuel.ours.data.sms.Categorizer
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.TxnType
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Throwaway harness: replays a corpus of real messages exported from the device and
 * prints a quality report. Skips silently when the corpus file is absent, so it never
 * breaks a normal build and no personal data is ever committed to the repo.
 *
 * Point it at a corpus with:
 *   ./gradlew testDebugUnitTest --tests '*CorpusReportTest*' -Dcorpus=/path/to/corpus.tsv
 */
class CorpusReportTest {

    private val parser = SmsParser()

    @Test
    fun `replay corpus and report quality`() {
        val path = System.getProperty("corpus") ?: return
        val file = File(path)
        assumeTrue("corpus file not found: $path", file.exists())

        val rules = Categorizer.seedEntities()
        val now = System.currentTimeMillis()

        var expenses = 0
        var uncategorised = 0
        var numericMerchant = 0
        var nullMerchant = 0
        val ignored = mutableMapOf<String, Int>()
        var spendPaise = 0L
        var cardPayPaise = 0L
        var transferPaise = 0L
        var unrecognised = 0
        val badMerchants = mutableListOf<String>()

        file.readLines().filter { it.isNotBlank() }.forEach { line ->
            val (sender, body) = line.split("\t", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (val r = parser.parse(sender, body, now)) {
                is SmsParser.Result.Expense -> {
                    expenses++
                    val t = r.txn
                    val cat = when (t.kind) {
                        SmsParser.Kind.CARD_BILL_PAYMENT -> Category.CARD_PAYMENT
                        SmsParser.Kind.SAVINGS_DEPOSIT -> Category.INVESTMENTS
                        SmsParser.Kind.TRANSFER -> Category.TRANSFERS
                        SmsParser.Kind.PURCHASE ->
                            Categorizer.categorize(t.merchant, t.type, rules)
                    }
                    if (t.merchant == null) nullMerchant++
                    else if (t.merchant!!.none { it.isLetter() }) {
                        numericMerchant++; badMerchants += t.merchant!!
                    }
                    if (cat == Category.OTHER) uncategorised++

                    // Mirror the app exactly: the harness must not invent its own
                    // accounting, or it reports a number the user never sees.
                    if (t.type == TxnType.DEBIT) {
                        when {
                            cat == Category.CARD_PAYMENT -> cardPayPaise += t.amountPaise
                            cat == Category.TRANSFERS -> transferPaise += t.amountPaise
                            else -> Unit
                        }
                        if (cat.countsAsSpending) spendPaise += t.amountPaise
                    }
                }
                is SmsParser.Result.BillReminder ->
                    ignored.merge("BILL_REMINDER", 1, Int::plus)
                // Payment-shaped, sender unvouched: a question, not a result.
                is SmsParser.Result.Unrecognised -> unrecognised++
                is SmsParser.Result.Ignored ->
                    ignored.merge(r.reason.name, 1, Int::plus)
            }
        }

        println("\n================ CORPUS REPORT ================")
        println("  parsed as expenses        : $expenses")
        println("  merchant = null (no payee): $nullMerchant")
        println("  merchant = numeric junk   : $numericMerchant $badMerchants")
        println("  category = OTHER          : $uncategorised")
        println("  ---- rejected ----")
        ignored.toList().sortedByDescending { it.second }.forEach { (k, v) -> println("  $k: $v") }
        println("  ---- money (whole corpus) ----")
        println("  counted as spending        : ${Money.format(spendPaise)}")
        println("  excluded: card bill payments: ${Money.format(cardPayPaise)}")
        println("  excluded: unlabelled transfers: ${Money.format(transferPaise)}")
        println("  raw sum of all debits      : " +
            Money.format(spendPaise + cardPayPaise + transferPaise))
        println("===============================================\n")
    }
}
