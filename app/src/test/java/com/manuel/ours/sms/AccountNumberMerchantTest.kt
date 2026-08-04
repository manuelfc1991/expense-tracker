package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.SmsParser
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * An account number is not a payee.
 *
 * Kerala Gramin — the household's main bank — words every UPI debit as "credited to
 * a/c no. XXXX", naming the destination account rather than the person. The `from`
 * pattern already refused to read an account as a merchant; the `to` pattern did not,
 * so 189 of 460 real transactions were filed under a merchant literally called
 * "a/c no", and every one of them landed in Other because no rule can match that.
 */
class AccountNumberMerchantTest {

    private val parser = SmsParser()

    private fun parse(sender: String, body: String) =
        runBlocking { parser.parse(sender, body, System.currentTimeMillis()) }

    private fun merchantOf(sender: String, body: String): String? =
        (parse(sender, body) as? SmsParser.Result.Expense)?.txn?.merchant

    @Test
    fun `kerala gramin upi debit does not name the destination account as the payee`() {
        val merchant = merchantOf(
            "BZ-KGBANK-S",
            "Your a/c no. XXXXX4657 is debited for Rs.100.00 on 19/07/26 05:52 PM and " +
                "credited to a/c no. XXXXX8891 (UPI Ref no 519012345678)-Kerala Gramin Bank",
        )
        // Null is the right answer: the bank named an account, not a payee, so the
        // row is honestly "Unknown payee" rather than a merchant that never existed.
        assertThat(merchant).isNull()
    }

    @Test
    fun `the no-space spelling is caught too`() {
        val merchant = merchantOf(
            "BX-KGBANK-S",
            "Your a/c no.XXXXX4657 is debited for Rs.10000.00 on 03/08/26 08:14 PM and " +
                "credited to a/c no.XXXXX8891(UPI Ref no 521987654321)-Kerala Grameena Bank",
        )
        assertThat(merchant).isNull()
    }

    @Test
    fun `account and acct spellings are refused as well`() {
        for (word in listOf("account no", "acct no", "ac no")) {
            val merchant = merchantOf(
                "BZ-KGBANK-S",
                "Your a/c is debited for Rs.250.00 on 19/07/26 and credited to " +
                    "$word. XXXXX8891 (UPI Ref no 519012345678)-Kerala Gramin Bank",
            )
            assertThat(merchant?.lowercase().orEmpty()).doesNotContain("no")
            assertThat(merchant?.lowercase().orEmpty()).doesNotContain("a/c")
        }
    }

    /**
     * The guard must not cost us real payees. "EMMANUEL AN" is a person the bank did
     * name, in the same sentence position, and it has to survive.
     */
    @Test
    fun `a named payee is still read`() {
        val merchant = merchantOf(
            "AD-FEDBNK-S",
            "Debited Rs 20.00 from a/c X4657 on 02Aug26 18:47 via UPI to EMMANUEL  AN. " +
                "Ref 521987654321.Bal Rs 2357.35. Not you?Call 18004201199 -Federal Bank",
        )
        assertThat(merchant).isEqualTo("Emmanuel An")
    }

    /**
     * The whole point of the exercise: these must still count as money spent.
     *
     * `detectKind` files an unnamed debit as a transfer, which is kept out of the
     * spending headline. Applied to Kerala Gramin's wording that would have moved a
     * household's largest group of real purchases out of its own total the moment the
     * merchant bug was fixed — a regression disguised as a fix.
     */
    @Test
    fun `an unnamed UPI debit still counts as spending`() {
        val result = parse(
            "BZ-KGBANK-S",
            "Your a/c no. XXXXX4657 is debited for Rs.100.00 on 19/07/26 05:52 PM and " +
                "credited to a/c no. XXXXX8891 (UPI Ref no 519012345678)-Kerala Gramin Bank",
        ) as SmsParser.Result.Expense

        assertThat(result.txn.kind).isEqualTo(SmsParser.Kind.PURCHASE)
    }

    /** A bare debit with no payee and no UPI reference stays ambiguous, as before. */
    @Test
    fun `a bare debit with no reference is still treated as a transfer`() {
        val result = parse(
            "AD-FEDBNK-S",
            "Debited Rs 20000 from a/c XXXXX4657 on 03AUG2026 21:03:55." +
                "Bal Rs 3357.35.Not you?Call 18004201199 -Federal Bank",
        ) as SmsParser.Result.Expense

        assertThat(result.txn.kind).isEqualTo(SmsParser.Kind.TRANSFER)
    }
}
