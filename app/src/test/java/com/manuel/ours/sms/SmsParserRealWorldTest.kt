package com.manuel.ours.sms

import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.TxnType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression suite built from **actual messages** pulled off the device, after the
 * first backfill produced a wrong monthly total.
 *
 * Every test here maps to a real defect: 113 of 177 transactions were uncategorised,
 * the ICICI fraud-report number had become the merchant on 38 rows, and ₹7,325 of
 * credit-card bill payments were being double-counted against the purchases that
 * made up those bills.
 */
class SmsParserRealWorldTest {

    private val parser = SmsParser()
    private val now = 1_785_000_000_000L

    private fun expense(sender: String, body: String): SmsParser.ParsedTxn {
        val result = parser.parse(sender, body, now)
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
        return (result as SmsParser.Result.Expense).txn
    }

    private fun reason(sender: String, body: String): SmsParser.Reason {
        val result = parser.parse(sender, body, now)
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        return (result as SmsParser.Result.Ignored).reason
    }

    // ---------------------------------------- ICICI cards: the 38-row merchant bug

    @Test
    fun `ICICI card spend takes the merchant after the date, not the helpline number`() {
        val t = expense(
            "VM-ICICIB",
            "INR 2,358.19 spent using ICICI Bank Card XX3008 on 19-Jul-26 on RELIANCE " +
                "SMART. Avl Limit: INR 5,150.03. If not you, call 1800 2662/SMS BLOCK " +
                "3008 to 9215676766.",
        )
        assertThat(t.merchant).isEqualTo("Reliance Smart")
        assertThat(t.amountPaise).isEqualTo(2_35_819)
        assertThat(t.type).isEqualTo(TxnType.DEBIT)
    }

    @Test
    fun `the fraud helpline number is never a merchant`() {
        val bodies = listOf(
            "INR 16.00 spent using ICICI Bank Card XX3008 on 17-Feb-26 on AMAZON PAY IN R. " +
                "Avl Limit: INR 5,487.00. If not you, call 1800 2662/SMS BLOCK 3008 to 9215676766.",
            "INR 320.02 spent using ICICI Bank Card XX3008 on 24-Apr-26 on MORE. " +
                "Avl Limit: INR 15,189.15. If not you, call 1800 2662/SMS BLOCK 3008 to 9215676766.",
            "INR 1,199.00 spent using ICICI Bank Card XX3008 on 24-Jun-26 on LENSKART SOLUTI. " +
                "Avl Limit: INR 22,931.98. If not you, call 1800 2662/SMS BLOCK 3008 to 9215676766.",
        )
        bodies.forEach { body ->
            val t = expense("VM-ICICIB", body)
            assertThat(t.merchant).isNotEqualTo("9215676766")
            assertThat(t.merchant!!.any { it.isLetter() }).isTrue()
        }
    }

    @Test
    fun `ICICI merchants resolve to their real names`() {
        fun merchantOf(raw: String) = expense("VM-ICICIB", raw).merchant

        assertThat(
            merchantOf(
                "INR 576.50 spent using ICICI Bank Card XX3008 on 05-Apr-26 on APOLLO " +
                    "PHARMACI. Avl Limit: INR 19,827.67. If not you, call 1800 2662/SMS " +
                    "BLOCK 3008 to 9215676766."
            )
        ).isEqualTo("Apollo Pharmaci")

        assertThat(
            merchantOf(
                "INR 1,608.00 spent using ICICI Bank Card XX3008 on 18-Apr-26 on ZUDIO A " +
                    "UNIT OF. Avl Limit: INR 18,949.17. If not you, call 1800 2662/SMS " +
                    "BLOCK 3008 to 9215676766."
            )
        ).isEqualTo("Zudio A Unit Of")

        assertThat(
            merchantOf(
                "INR 875.00 spent using ICICI Bank Card XX3008 on 21-May-26 on Flipkart. " +
                    "Avl Limit: INR 22,486.32. If not you, call 1800 2662/SMS BLOCK 3008 " +
                    "to 9215676766."
            )
        ).isEqualTo("Flipkart")
    }

    @Test
    fun `ICICI at-form with an EMI footer still parses`() {
        val t = expense(
            "VM-ICICIB",
            "Rs 16,941.00 spent on ICICI Bank Card XX3008 on 26-Feb-26 at Flipkart Intern. " +
                "Avl Lmt: Rs 10,300.00. To dispute, call 18002662/SMS BLOCK 3008 to " +
                "9215676766. To convert this txn to EMI give a missed call on 9924667667.",
        )
        assertThat(t.merchant).isEqualTo("Flipkart Intern")
        assertThat(t.amountPaise).isEqualTo(16_94_100)
    }

    // ------------------------------------------------ Federal Bank UPI: the good path

    @Test
    fun `Federal Bank UPI debit names the payee`() {
        val t = expense(
            "AD-FEDBNK",
            "Debited Rs 151.00 from a/c X4657 on 01Jul26 07:48 via UPI to KEECHERIL ST. " +
                "Ref 618233824289.Bal Rs 3469.55. Not you?Call 18004251199 -Federal Bank",
        )
        assertThat(t.merchant).isEqualTo("Keecheril St")
        assertThat(t.amountPaise).isEqualTo(15_100)
        assertThat(t.refNo).isEqualTo("618233824289")
        assertThat(t.balancePaise).isEqualTo(3_46_955)
        assertThat(t.kind).isEqualTo(SmsParser.Kind.PURCHASE)
    }

    @Test
    fun `compact Federal Bank date is used instead of the delivery time`() {
        val t = expense(
            "AD-FEDBNK",
            "Debited Rs 210.00 from a/c X4657 on 04Jul26 16:53 via UPI to RAMSON NADAR. " +
                "Ref 618587933197.Bal Rs 3142.55. Not you?Call 18004251199 -Federal Bank",
        )
        // A backfilled message must land on the day it happened, not the day it was read.
        assertThat(t.occurredAt).isNotEqualTo(now)
    }

    // ------------------------------------------------------ payee-less bank debits

    @Test
    fun `a debit with no payee is a transfer, not a purchase at the bank`() {
        val t = expense(
            "AD-FEDBNK",
            "Debited Rs 30000 from a/c XX4657 on 02JUL2026 22:57:32.Bal Rs 3572.55." +
                "Not you?Call 18004251199 -Federal Bank",
        )
        // The message names nobody. Guessing "Federal Bank" as the merchant was both
        // wrong and, once recategorised, poisoned the learned rules.
        assertThat(t.merchant).isNull()
        assertThat(t.kind).isEqualTo(SmsParser.Kind.TRANSFER)
        assertThat(t.amountPaise).isEqualTo(30_00_000)
    }

    @Test
    fun `standing instruction is a transfer`() {
        val t = expense(
            "AD-FEDBNK",
            "Debited Rs 1000 from a/c XX4657 on 10FEB2026 and FSF a/c XX0165 credited as " +
                "per standing instruction.BalRs 52013.50.Not you?Call 18004251199 -Federal Bank",
        )
        assertThat(t.kind).isEqualTo(SmsParser.Kind.TRANSFER)
    }

    // ------------------------------------------------------- credit-card bill payments

    @Test
    fun `card bill payment is flagged so it does not double-count the purchases`() {
        val t = expense(
            "VM-ICICIB",
            "Payment of Rs 7,125.65 has been received on your ICICI Bank Credit Card " +
                "XX3008 through Bharat Bill Payment System on 02-JUL-26.",
        )
        assertThat(t.kind).isEqualTo(SmsParser.Kind.CARD_BILL_PAYMENT)
        assertThat(t.amountPaise).isEqualTo(7_12_565)
    }

    // --------------------------------------------------------------- incoming credits

    @Test
    fun `credit does not treat the word your as the counterparty`() {
        val t = expense(
            "AD-FEDBNK",
            "Dear Customer, Rs.22 credited to your A/c XX4657 on 02FEB2026 10:52:41. " +
                "BAL-Rs.5947.50-Federal Bank",
        )
        assertThat(t.type).isEqualTo(TxnType.CREDIT)
        assertThat(t.merchant).isNotEqualTo("your")
    }

    @Test
    fun `NEFT credit names the sender`() {
        val t = expense(
            "AD-FEDBNK",
            "Rs 2003.63 credited to your A/c XX4657 via NEFT from INDIAN CLE on " +
                "23JUN2026 11:06:26 Ref No HDFCH01076561555 Bal:Rs 5089.11 -Federal Bank",
        )
        assertThat(t.type).isEqualTo(TxnType.CREDIT)
        assertThat(t.merchant).isEqualTo("Indian Cle")
    }

    @Test
    fun `credit from a named person is attributed to them`() {
        val t = expense(
            "AD-FEDBNK",
            "Dear Customer, Rs.10000 credited to your A/c XX4657 from MANUEL FRA on " +
                "02JUL2026 22:56:14. BAL-Rs.33572.55-Federal Bank",
        )
        assertThat(t.merchant).isEqualTo("Manuel Fra")
    }

    // ----------------------------------------------- things that are not transactions

    @Test
    fun `credit limit change is not income`() {
        // "credit limit" used to match a bare "credit" verb and became phantom income.
        assertThat(
            reason(
                "VM-ICICIB",
                "Dear Customer, The credit limit for your ICICI Bank Credit Card " +
                    "4315X3008 has been changed from INR 80000 to INR 110000 on 2026-07-11.",
            )
        ).isEqualTo(SmsParser.Reason.NOT_A_TRANSACTION)
    }

    @Test
    fun `limit increase marketing is not an expense`() {
        assertThat(
            reason(
                "VM-ICICIB",
                "Manage spends effectively by increasing the limit on ICICI Bank Credit " +
                    "Card XX3008 from Rs80000 to Rs110000. SMS CRLIM 3008 to 5676766 to " +
                    "raise the limit",
            )
        ).isEqualTo(SmsParser.Reason.NOT_A_TRANSACTION)
    }

    @Test
    fun `EMI conversion does not re-bill a purchase already recorded`() {
        assertThat(
            reason(
                "VM-ICICIB",
                "Dear Customer, your transaction of Rs 16,941.00 using ICICI Bank Credit " +
                    "Card XX3008 has been converted into EMI on 28-02-26.",
            )
        ).isEqualTo(SmsParser.Reason.NOT_A_TRANSACTION)
    }

    @Test
    fun `statement notice becomes a bill reminder, not an expense`() {
        val result = parser.parse(
            "VM-ICICIB",
            "ICICI Bank Credit Card XX3008 Statement is sent to ma********ya@gmail.com. " +
                "Total of Rs 729.50 or minimum of Rs 100.00 is due by 30-APR-26.",
            now,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.BillReminder::class.java)
    }

    // ------------------------------------------------------------------- fee messages

    @Test
    fun `bank fee keeps its description as the merchant`() {
        val t = expense(
            "AD-FEDBNK",
            "Hi,Rs.8 debited from your A/c XX4657 on 22APR2026 07:29:05 towards IMPS " +
                "charges. BAL-Rs.11943.26-Federal Bank",
        )
        assertThat(t.merchant).isEqualTo("IMPS charges")
        assertThat(t.amountPaise).isEqualTo(800)
    }

    // -------------------------------------------------------------- noise-tail stripping

    @Test
    fun `noise tail is removed before merchant extraction`() {
        val stripped = parser.stripNoiseTail(
            "INR 49.00 spent using ICICI Bank Card XX3008 on 19-Jul-26 on FINEFAIR INDIA . " +
                "Avl Limit: INR 5,101.03. If not you, call 1800 2662/SMS BLOCK 3008 to 9215676766."
        )
        assertThat(stripped).doesNotContain("9215676766")
        assertThat(stripped).doesNotContain("Avl Limit")
        assertThat(stripped).contains("FINEFAIR INDIA")
    }

    @Test
    fun `amount and balance still parse after the tail is stripped for merchants`() {
        // stripNoiseTail must not be applied to amount extraction — the balance lives
        // inside the tail and is still wanted.
        val t = expense(
            "AD-FEDBNK",
            "Debited Rs 171.00 from a/c X4657 on 23Jul26 18:02 via UPI to SANTOSH S. " +
                "Ref 657083496641.Bal Rs 4224.68. Not you?Call 18004251199 -Federal Bank",
        )
        assertThat(t.amountPaise).isEqualTo(17_100)
        assertThat(t.balancePaise).isEqualTo(4_22_468)
        assertThat(t.merchant).isEqualTo("Santosh S")
    }
}
