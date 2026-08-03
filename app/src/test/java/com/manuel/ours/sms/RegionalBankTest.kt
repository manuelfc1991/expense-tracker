package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Regional and small-finance banks, and the salary credits that arrive from them.
 *
 * This exists because of a real failure: the app was tested against the big private
 * banks and shipped without Kerala Gramin Bank, whose header carried 466 of the
 * messages on the first phone it ran on. Sender matching is the *first* rule in
 * [SmsParser.parse], so every one of those was discarded before an amount was ever
 * looked for — the app was not mis-parsing them, it was refusing to read them.
 *
 * A household banks with one bank. A missing header is therefore never a small gap;
 * it is that household's entire history.
 */
class RegionalBankTest {

    private val parser = SmsParser()

    @Test
    fun `regional and small-finance headers are recognised`() {
        val expected = mapOf(
            "AX-KGBANK-S" to "Kerala Gramin Bank",
            "BZ-KGBANK-S" to "Kerala Gramin Bank",
            "AD-KGBANK" to "Kerala Gramin Bank",
            "BT-UTKBNK-S" to "Utkarsh Small Finance Bank",
            "VM-KARBNK-S" to "Karnataka Bank",
            "AD-SIBSMS-S" to "South Indian Bank",
            "AX-INDBNK-S" to "Indian Bank",
            "JD-CBINDI-S" to "Central Bank of India",
            "AD-IOBCHN-S" to "Indian Overseas Bank",
            "VM-UCOBNK-S" to "UCO Bank",
            "AD-BANDHN-S" to "Bandhan Bank",
            "AX-AUBANK-S" to "AU Small Finance Bank",
            "AD-RBLBNK-S" to "RBL Bank",
            "JX-MAHABK-S" to "Bank of Maharashtra",
        )
        expected.forEach { (sender, bank) ->
            assertThat(BankRules.forSender(sender)?.bank).isEqualTo(bank)
        }
    }

    @Test
    fun `payment gateways and paytm bank headers are recognised`() {
        listOf("CP-JUSPAY-S", "JL-JioPay-S", "AD-PYTMBK-S", "AX-iPaytm-S", "AD-NPCIBC-S")
            .forEach { assertThat(BankRules.forSender(it)).isNotNull() }
    }

    @Test
    fun `a gramin bank salary credit parses as income`() {
        val result = parser.parse(
            "AX-KGBANK-S",
            "Dear Customer, Your A/c XXXXX4321 is credited by Rs.42,500.00 on 01-08-2025 " +
                "by transfer from ACME EXPORTS PVT LTD. Avl Bal Rs.51,230.75 -Kerala Gramin Bank",
            System.currentTimeMillis(),
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
        val txn = (result as SmsParser.Result.Expense).txn
        assertThat(txn.type).isEqualTo(TxnType.CREDIT)
        assertThat(txn.amountPaise).isEqualTo(42_500_00)
        assertThat(txn.bank).isEqualTo("Kerala Gramin Bank")
        // The closing balance must never be mistaken for the amount.
        assertThat(txn.balancePaise).isEqualTo(51_230_75)
    }

    @Test
    fun `a gramin bank upi debit parses as spending`() {
        val result = parser.parse(
            "BZ-KGBANK-S",
            "Rs.451.00 debited from A/c XXXXX4321 on 02-08-2025 to VPA merchant@okicici " +
                "UPI Ref 522334455667. Avl Bal Rs.50,779.75 -Kerala Gramin Bank",
            System.currentTimeMillis(),
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
        val txn = (result as SmsParser.Result.Expense).txn
        assertThat(txn.type).isEqualTo(TxnType.DEBIT)
        assertThat(txn.amountPaise).isEqualTo(451_00)
        assertThat(txn.refNo).isEqualTo("522334455667")
    }

    @Test
    fun `an OTP from a newly added bank is still rejected`() {
        // Adding a header must not weaken the reject rules that run after it.
        val result = parser.parse(
            "AX-KGBANK-S",
            "OTP for txn of Rs.4,821 at AMAZON is 998877. Do not share it with anyone.",
            System.currentTimeMillis(),
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        assertThat((result as SmsParser.Result.Ignored).reason).isEqualTo(SmsParser.Reason.OTP)
    }

    @Test
    fun `bank names are recognised as fallback labels, not merchants`() {
        // A bare credit is labelled with its bank so the row reads as something rather
        // than "Unknown payee". That label must never be learned as a merchant rule, or
        // categorising one salary would relabel every later refund from the same bank.
        assertThat(BankRules.isBankName("Kerala Gramin Bank")).isTrue()
        assertThat(BankRules.isBankName("kerala gramin bank")).isTrue()
        assertThat(BankRules.isBankName("  Federal Bank  ")).isTrue()
        assertThat(BankRules.isBankName("State Bank of India")).isTrue()

        // Real merchants must stay learnable, including ones that contain "bank".
        assertThat(BankRules.isBankName("Keecheril St")).isFalse()
        assertThat(BankRules.isBankName("Bank Street Cafe")).isFalse()
        assertThat(BankRules.isBankName("Unknown payee")).isFalse()
        assertThat(BankRules.isBankName(null)).isFalse()
    }

    @Test
    fun `every bank name is covered by the fallback guard`() {
        // Guards against adding a bank above and forgetting that its name can now
        // appear in the merchant column.
        BankRules.ALL.forEach { rule ->
            assertThat(BankRules.isBankName(rule.bank)).isTrue()
        }
    }

    @Test
    fun `a personal number is still never parsed whatever it says`() {
        val result = parser.parse(
            "+919876543210",
            "Rs.5000 credited to your account by SALARY",
            System.currentTimeMillis(),
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        assertThat((result as SmsParser.Result.Ignored).reason)
            .isEqualTo(SmsParser.Reason.UNKNOWN_SENDER)
    }
}
