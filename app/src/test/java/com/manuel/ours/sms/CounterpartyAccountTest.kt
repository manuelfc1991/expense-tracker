package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.SmsParser
import org.junit.Test

/**
 * The account a payment went to, as an identifier rather than a payee.
 *
 * Kerala Gramin words a payment to a mother, a landlord, a fixed deposit and one's own
 * second account in identical language — "debited ... and credited to a/c no. XXXX".
 * No parser can tell them apart, and 96 rows on a real ledger read "Unknown payee" for
 * exactly that reason. The number is the only thing that differs between them, which
 * makes it the only thing a household-given name can hang on.
 */
class CounterpartyAccountTest {

    private val parser = SmsParser()

    @Test
    fun `the destination account is read from a real kerala gramin debit`() {
        assertThat(
            parser.extractCounterpartyTail(
                "Your a/c no. XXXXX4657 is debited for Rs.1160.00 on 11/07/26 10:48 PM " +
                    "and credited to a/c no. XXXXX8891 (UPI Ref no 519012345678)" +
                    "-Kerala Gramin Bank"
            )
        ).isEqualTo("8891")
    }

    @Test
    fun `the no-space spelling reads the same`() {
        assertThat(
            parser.extractCounterpartyTail(
                "Your a/c no.XXXXX4657 is debited for Rs.10000.00 on 03/08/26 08:14 PM " +
                    "and credited to a/c no.XXXXX8891(UPI Ref no 521987654321)" +
                    "-Kerala Grameena Bank"
            )
        ).isEqualTo("8891")
    }

    /**
     * The account the message is *addressed to* is the household's own, and is already
     * captured separately. Reading it here would name every payment after the payer.
     */
    @Test
    fun `the senders own account is never mistaken for the destination`() {
        assertThat(
            parser.extractCounterpartyTail(
                "Debited Rs 20000 from a/c XXXXX4657 on 03AUG2026 21:03:55." +
                    "Bal Rs 3357.35.Not you?Call 18004201199 -Federal Bank"
            )
        ).isNull()
    }

    @Test
    fun `a message naming no destination yields nothing`() {
        assertThat(
            parser.extractCounterpartyTail(
                "Your A/c XXXXX4657 debited Rs.7177.79 for / Bal after txn Rs 51022.9 " +
                    "Msg Id 123456 Time 03-08-2026 19:43:47 -Kerala Grameena Bank"
            )
        ).isNull()
    }

    @Test
    fun `too few digits is not distinctive enough to name`() {
        assertThat(
            parser.extractCounterpartyTail("debited Rs.100 and credited to a/c no. 12")
        ).isNull()
    }

    @Test
    fun `a longer account number keeps the last four digits`() {
        assertThat(
            parser.extractCounterpartyTail("credited to a/c no. 123456789012")
        ).isEqualTo("9012")
    }
}
