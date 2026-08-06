package com.manuel.ours.sms

import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.TxnType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Real-world message shapes. Every one of these was a bug in some expense tracker.
 *
 * The negative cases matter as much as the positive ones: an OTP logged as a ₹4,821
 * expense is worse than a missed transaction, because you have to hunt it down.
 */
class SmsParserTest {

    private val parser = SmsParser()
    private val now = 1_720_000_000_000L

    private fun expense(sender: String, body: String): SmsParser.ParsedTxn {
        val result = parser.parse(sender, body, now)
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
        return (result as SmsParser.Result.Expense).txn
    }

    private fun ignored(sender: String, body: String): SmsParser.Reason {
        val result = parser.parse(sender, body, now)
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        return (result as SmsParser.Result.Ignored).reason
    }

    // ---------------------------------------------------------------- debits

    @Test
    fun `HDFC UPI debit`() {
        val t = expense(
            "AD-HDFCBK",
            "Rs.450.00 debited from a/c XXXX1234 on 12-05-24 to VPA swiggy@ybl " +
                "UPI Ref 412345678901. Not you? Call 18002586161",
        )
        assertThat(t.amountPaise).isEqualTo(45_000)
        assertThat(t.type).isEqualTo(TxnType.DEBIT)
        assertThat(t.merchant).isEqualTo("swiggy@ybl")
        assertThat(t.accountTail).isEqualTo("1234")
        assertThat(t.refNo).isEqualTo("412345678901")
    }

    @Test
    fun `ICICI card spend with merchant`() {
        val t = expense(
            "VM-ICICIB",
            "INR 2,499.00 spent on ICICI Bank Card XX9012 on 03-Jun-24 at AMAZON. " +
                "Avl Lmt: INR 47,501.00",
        )
        assertThat(t.amountPaise).isEqualTo(2_49_900)
        assertThat(t.merchant).isEqualTo("Amazon")
        assertThat(t.accountTail).isEqualTo("9012")
    }

    @Test
    fun `SBI debit with Info field`() {
        val t = expense(
            "JD-SBIINB",
            "Dear Customer, Rs.1,250.00 debited from A/c no. XX5678 on 15-06-24 " +
                "Info: BIG BAZAAR. Avl Bal Rs.23,450.75",
        )
        assertThat(t.amountPaise).isEqualTo(1_25_000)
        assertThat(t.merchant).isEqualTo("Big Bazaar")
        assertThat(t.balancePaise).isEqualTo(23_45_075)
    }

    @Test
    fun `Axis Bank rupee symbol`() {
        val t = expense(
            "AX-AXISBK",
            "₹899 debited from A/c XX4321 towards NETFLIX on 01-07-24. Bal ₹12,340",
        )
        assertThat(t.amountPaise).isEqualTo(89_900)
        assertThat(t.merchant).isEqualTo("Netflix")
    }

    @Test
    fun `Kotak ATM withdrawal`() {
        val t = expense(
            "VK-KOTAKB",
            "Rs 5000 withdrawn from Kotak A/c X7788 at ATM on 09/07/24. Avl Bal Rs 15200.00",
        )
        assertThat(t.amountPaise).isEqualTo(5_00_000)
        assertThat(t.type).isEqualTo(TxnType.DEBIT)
    }

    @Test
    fun `PhonePe payment to a person`() {
        val t = expense(
            "JD-PHONPE",
            "You paid Rs.320 to RAMESH KUMAR via PhonePe UPI Ref 987654321012",
        )
        assertThat(t.amountPaise).isEqualTo(32_000)
        assertThat(t.merchant).isEqualTo("Ramesh Kumar")
    }

    @Test
    fun `GPay merchant payment`() {
        val t = expense(
            "VM-GPAY",
            "Rs.150.00 paid to ZEPTO MARKETPLACE. UPI transaction ID 445566778899",
        )
        assertThat(t.amountPaise).isEqualTo(15_000)
        assertThat(t.merchant).isEqualTo("Zepto Marketplace")
    }

    @Test
    fun `Paytm wallet debit`() {
        val t = expense(
            "AD-PAYTMB",
            "Rs.99 paid at UBER INDIA using Paytm Wallet. Balance: Rs.401",
        )
        assertThat(t.amountPaise).isEqualTo(9_900)
        assertThat(t.merchant).isEqualTo("Uber India")
    }

    @Test
    fun `amount with no decimals and comma grouping`() {
        val t = expense("AD-HDFCBK", "Rs.1,23,456 debited from a/c XX1111 at IKEA")
        assertThat(t.amountPaise).isEqualTo(1_23_45_600)
    }

    @Test
    fun `amount before the currency word`() {
        val t = expense("VM-INDUSB", "2500.50 INR debited from your account XX2222 at CROMA")
        assertThat(t.amountPaise).isEqualTo(2_50_050)
    }

    @Test
    fun `IDFC First fuel spend`() {
        val t = expense(
            "AD-IDFCFB",
            "INR 3,000.00 spent using IDFC FIRST Bank Card XX8899 at INDIANOIL on 22-06-24",
        )
        assertThat(t.merchant).isEqualTo("Indianoil")
        assertThat(t.amountPaise).isEqualTo(3_00_000)
    }

    @Test
    fun `Federal Bank NEFT transfer out`() {
        val t = expense(
            "AD-FEDBNK",
            "Rs 15000.00 transferred to PRIYA SHARMA from A/c XX3344 via NEFT. Ref 556677889900",
        )
        assertThat(t.amountPaise).isEqualTo(15_00_000)
        assertThat(t.type).isEqualTo(TxnType.DEBIT)
    }

    @Test
    fun `Yes Bank card purchase`() {
        val t = expense(
            "JM-YESBNK",
            "Your card XX5566 was used for a purchase of Rs.749.00 at BOOKMYSHOW",
        )
        assertThat(t.merchant).isEqualTo("Bookmyshow")
    }

    @Test
    fun `slice card spend`() {
        val t = expense("AD-SLICEIT", "Rs.1,299 spent at MYNTRA on your slice card")
        assertThat(t.amountPaise).isEqualTo(1_29_900)
        assertThat(t.merchant).isEqualTo("Myntra")
    }

    @Test
    fun `PNB debit with a slash date`() {
        val t = expense(
            "BZ-PNBSMS",
            "Rs.640.00 debited from A/c XX7890 on 18/06/2024 at RELIANCE FRESH",
        )
        assertThat(t.merchant).isEqualTo("Reliance Fresh")
    }

    @Test
    fun `Canara Bank UPI debit`() {
        val t = expense(
            "AD-CANBNK",
            "An amount of Rs 210.00 has been debited from your a/c XX4455 " +
                "towards UPI/rapido@axl on 20-06-24",
        )
        assertThat(t.amountPaise).isEqualTo(21_000)
    }

    @Test
    fun `Bank of India debit`() {
        val t = expense(
            "VM-BOIIND",
            "Rs.2,000.00 has been debited from your account XX6677 at DMART on 25-06-24",
        )
        assertThat(t.merchant).isEqualTo("Dmart")
    }

    @Test
    fun `CRED credit card bill payment`() {
        val t = expense("VM-CRED", "Rs.24,500 paid towards HDFC CREDIT CARD via CRED")
        assertThat(t.amountPaise).isEqualTo(24_50_000)
    }

    @Test
    fun `Amazon Pay balance debit`() {
        val t = expense("AD-AMZNPY", "Rs.299.00 spent at HOTSTAR using Amazon Pay balance")
        assertThat(t.merchant).isEqualTo("Hotstar")
    }

    @Test
    fun `merchant name containing an ampersand`() {
        val t = expense("AD-HDFCBK", "Rs.560 spent at DOMINOS & PIZZA on 11-06-24")
        assertThat(t.merchant).isEqualTo("Dominos & Pizza")
    }

    // --------------------------------------------------------------- credits

    @Test
    fun `salary credit`() {
        val t = expense(
            "AD-HDFCBK",
            "Rs.85,000.00 credited to A/c XX1234 on 01-07-24 towards SALARY. Avl Bal Rs.92,300",
        )
        assertThat(t.type).isEqualTo(TxnType.CREDIT)
        assertThat(t.amountPaise).isEqualTo(85_00_000)
    }

    @Test
    fun `UPI money received`() {
        val t = expense(
            "JD-PHONPE",
            "You have received Rs.500 from ANITA in your account. UPI Ref 112233445566",
        )
        assertThat(t.type).isEqualTo(TxnType.CREDIT)
    }

    @Test
    fun `interest credited`() {
        val t = expense("JD-SBIINB", "Rs.1,204.00 credited to A/c XX5678 as interest on 30-06-24")
        assertThat(t.type).isEqualTo(TxnType.CREDIT)
    }

    // ------------------------------------------------------- must be ignored

    @Test
    fun `plain OTP is not an expense`() {
        assertThat(ignored("AD-HDFCBK", "123456 is your OTP for login. Do not share with anyone."))
            .isEqualTo(SmsParser.Reason.OTP)
    }

    @Test
    fun `OTP that mentions an amount is still not an expense`() {
        assertThat(
            ignored(
                "VM-ICICIB",
                "OTP for txn of Rs.4,821.00 at AMAZON is 998877. Valid for 10 mins. " +
                    "Do not share this OTP.",
            )
        ).isEqualTo(SmsParser.Reason.OTP)
    }

    @Test
    fun `3D secure verification code ignored`() {
        assertThat(
            ignored("AX-AXISBK", "Your verification code is 445566 for a purchase of Rs.2,300")
        ).isEqualTo(SmsParser.Reason.OTP)
    }

    @Test
    fun `failed transaction ignored`() {
        assertThat(
            ignored("AD-HDFCBK", "Your transaction of Rs.1,500 at FLIPKART has failed.")
        ).isEqualTo(SmsParser.Reason.FAILED_TRANSACTION)
    }

    @Test
    fun `declined card ignored`() {
        assertThat(
            ignored("VM-ICICIB", "Transaction of INR 5,000 on card XX1234 was declined.")
        ).isEqualTo(SmsParser.Reason.FAILED_TRANSACTION)
    }

    @Test
    fun `reversal ignored`() {
        assertThat(
            ignored("JD-SBIINB", "Rs.899 debited on 04-06-24 has been reversed to your A/c XX5678")
        ).isEqualTo(SmsParser.Reason.FAILED_TRANSACTION)
    }

    @Test
    fun `promotional loan offer ignored`() {
        assertThat(
            ignored(
                "AD-HDFCBK",
                "You are pre-approved for a personal loan of Rs.5,00,000 at lowest interest. " +
                    "Apply now!",
            )
        ).isEqualTo(SmsParser.Reason.PROMOTIONAL)
    }

    @Test
    fun `promotional sale ignored`() {
        assertThat(
            ignored("VM-MYNTRA", "Flat 60% discount! Sale ends today. Shop now for Rs.999 only")
        ).isEqualTo(SmsParser.Reason.UNKNOWN_SENDER)
    }

    @Test
    fun `cashback offer ignored`() {
        assertThat(
            ignored("AD-PAYTMB", "Get cashback up to Rs.500 on your next recharge. T&C apply.")
        ).isEqualTo(SmsParser.Reason.PROMOTIONAL)
    }

    @Test
    fun `balance enquiry only ignored`() {
        assertThat(
            ignored("AD-HDFCBK", "Avl Bal in your A/c XX1234 as on 12-06-24 is Rs.45,200.00")
        ).isEqualTo(SmsParser.Reason.BALANCE_ENQUIRY_ONLY)
    }

    @Test
    fun `personal phone number is never parsed`() {
        assertThat(
            ignored("+919876543210", "Hey, I paid Rs.500 for the dinner, send it across")
        ).isEqualTo(SmsParser.Reason.UNKNOWN_SENDER)
    }

    /**
     * Bank-shaped is not enough on its own: this names no bank, so it stays discarded.
     * [SenderDiscoveryTest] covers the unknown header that does name one.
     */
    @Test
    fun `unknown brand sender ignored`() {
        assertThat(ignored("AD-ZZZZZZ", "Rs.100 debited from a/c XX1111 at SOMEWHERE"))
            .isEqualTo(SmsParser.Reason.UNKNOWN_SENDER)
    }

    @Test
    fun `no amount means no expense`() {
        assertThat(ignored("AD-HDFCBK", "Your account XX1234 has been debited. Check the app."))
            .isEqualTo(SmsParser.Reason.NO_AMOUNT)
    }

    // ---------------------------------------------------------- bill reminders

    @Test
    fun `credit card bill due becomes a reminder not an expense`() {
        val result = parser.parse(
            "VM-ICICIB",
            "Total amount due Rs.12,450.00 on your card XX9012 is due on 15-07-24.",
            now,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.BillReminder::class.java)
        val reminder = result as SmsParser.Result.BillReminder
        assertThat(reminder.amountPaise).isEqualTo(12_45_000)
    }

    @Test
    fun `electricity bill due becomes a reminder`() {
        val result = parser.parse(
            "AD-HDFCBK",
            "Your BESCOM bill of Rs.2,340 is due on 20-07-24. Pay now to avoid late fee.",
            now,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.BillReminder::class.java)
    }

    @Test
    fun `a paid bill is an expense not a reminder`() {
        val t = expense(
            "AD-HDFCBK",
            "Rs.2,340 debited from A/c XX1234 towards BESCOM bill payment on 18-07-24",
        )
        assertThat(t.amountPaise).isEqualTo(2_34_000)
    }

    // ------------------------------------------------------------- edge cases

    @Test
    fun `balance is not mistaken for the amount`() {
        val t = expense(
            "AD-HDFCBK",
            "Avl Bal Rs.50,000. Rs.250 debited from a/c XX1234 at CAFE COFFEE DAY",
        )
        assertThat(t.amountPaise).isEqualTo(25_000)
        assertThat(t.balancePaise).isEqualTo(50_00_000)
    }

    @Test
    fun `lowercase sender header still resolves`() {
        val t = expense("ad-hdfcbk", "Rs.100 debited from a/c XX1234 at TEST SHOP")
        assertThat(t.bank).isEqualTo("HDFC Bank")
    }

    @Test
    fun `sender without operator prefix resolves`() {
        val t = expense("HDFCBK", "Rs.100 debited from a/c XX1234 at TEST SHOP")
        assertThat(t.bank).isEqualTo("HDFC Bank")
    }

    @Test
    fun `date in the body wins over the received time`() {
        val t = expense("AD-HDFCBK", "Rs.100 debited from a/c XX1234 at SHOP on 01-01-24")
        assertThat(t.occurredAt).isNotEqualTo(now)
        assertThat(t.occurredAt).isLessThan(now)
    }

    @Test
    fun `missing date falls back to the received time`() {
        val t = expense("AD-HDFCBK", "Rs.100 debited from a/c XX1234 at SHOP")
        assertThat(t.occurredAt).isEqualTo(now)
    }

    @Test
    fun `merchant is null rather than wrong when unparseable`() {
        val t = expense("AD-HDFCBK", "Rs.100 debited from a/c XX1234.")
        assertThat(t.merchant).isNull()
    }

    @Test
    fun `merchant does not swallow the trailing sentence`() {
        val t = expense(
            "AD-HDFCBK",
            "Rs.560 spent at STARBUCKS on 11-06-24. Not you? Call 18002586161 immediately.",
        )
        assertThat(t.merchant).isEqualTo("Starbucks")
    }

    @Test
    fun `already mixed-case merchant is left alone`() {
        val t = expense("AD-HDFCBK", "Rs.560 spent at Cafe Coffee Day on 11-06-24")
        assertThat(t.merchant).isEqualTo("Cafe Coffee Day")
    }
}
