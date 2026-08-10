package com.manuel.ours.ui

import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.domain.model.MoveSide
import com.manuel.ours.ui.screens.home.MoveMoneyForm
import com.manuel.ours.ui.theme.OursTheme
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sheet that records money going from one of the household's own places to another.
 *
 * Its rules are all things a person can only discover by tapping, which is how they were checked
 * until now — on a real phone, over a slow link, one screenshot at a time. Three of them decide
 * whether the feature can be used correctly at all:
 *
 * - **Save waits for a complete statement.** A move with one end, or no amount, is not a move.
 * - **The same place cannot be both ends.** Picking it as the source clears it as the
 *   destination, because the tap just made is the one that was meant.
 * - **The second amount is offered only for a card**, which is the CRED case — ₹425.41 leaves the
 *   bank and ₹468.41 reaches the card. Offering it everywhere would imply money routinely goes
 *   missing between two accounts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h2400dp")
class MoveMoneySheetTest {

    private val composeRule = createComposeRule()

    // Order matters: the assumption has to be evaluated before the compose rule tries to launch
    // an activity. See [ComposeOnDebugOnly] — as an `@Before` this silently did nothing.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeOnDebugOnly()).around(composeRule)

    private val compose get() = composeRule


    private fun account(key: String, bank: String, isCard: Boolean = false) = AccountBalance(
        key = key,
        accountTail = key,
        bank = bank,
        balancePaise = 10_000_00L,
        asOf = 1_800_000_000_000L,
        source = BalanceSource.BANK,
        isCard = isCard,
    )

    private val federal = account("4657", "Federal Bank")
    private val card = account("3008", "ICICI Bank", isCard = true)

    /** What the sheet handed back, or null if Save was never accepted. */
    private class Confirmed {
        var from: MoveSide? = null
        var to: MoveSide? = null
        var out: Long? = null
        var into: Long? = null
    }

    private fun show(accounts: List<AccountBalance> = listOf(federal, card)): Confirmed {
        val got = Confirmed()
        compose.setContent {
            OursTheme {
                MoveMoneyForm(
                    accounts = accounts,
                    onDismiss = {},
                    onConfirm = { from, to, out, into, _, _ ->
                        got.from = from; got.to = to; got.out = out; got.into = into
                    },
                )
            }
        }
        return got
    }

    private fun save() = compose.onNodeWithText("Save", ignoreCase = true)

    /**
     * The editable fields, in the order the form lays them out.
     *
     * Not `onNodeWithText("0")` — that is the *placeholder* drawn behind the amount field when it
     * is empty, a plain `Text` with no text-input action, so typing into it fails with a message
     * about `RequestFocus` that says nothing about the real cause.
     *
     * Order is amount, then "reached the card" when the destination is a card, then the note.
     * [expectFields] states the count so that adding a field breaks a test loudly rather than
     * silently shifting what these indices mean.
     */
    private fun fields() = compose.onAllNodes(hasSetTextAction())

    private fun expectFields(n: Int) {
        assertThat(fields().fetchSemanticsNodes().size).isEqualTo(n)
    }

    private fun amountField() = fields()[0]

    /** Only present when the destination is a card. */
    private fun reachedField() = fields()[1]

    /** The two ends are chips, and both lists offer every place money can sit. */
    private fun chips(label: String) =
        compose.onAllNodesWithText(label, substring = true, ignoreCase = true)

    // ── Save waits for a complete statement ───────────────────────────────────────────

    @Test
    fun `save is refused until there is an amount and two ends`() {
        show()
        save().assertIsNotEnabled()
    }

    @Test
    fun `save is refused with an amount but no accounts chosen`() {
        show()
        amountField().performTextInput("500")
        save().assertIsNotEnabled()
    }

    @Test
    fun `save is offered once the statement is complete`() {
        show()
        amountField().performTextInput("500")
        chips("Federal Bank")[0].performClick()
        chips("ICICI Bank")[1].performClick()
        save().assertIsEnabled()
    }

    // ── The same place cannot be both ends ────────────────────────────────────────────

    /**
     * Choosing the source clears a destination it collides with, so Save cannot become
     * available for a move from an account to itself — which is not a move and must never be
     * stored as one.
     */
    @Test
    fun `picking the same account for both ends leaves it incomplete`() {
        show()
        amountField().performTextInput("500")
        // Into Federal, then out of Federal — the second tap clears the first.
        chips("Federal Bank")[1].performClick()
        chips("Federal Bank")[0].performClick()
        save().assertIsNotEnabled()
    }

    // ── The second amount, and only for a card ────────────────────────────────────────

    /**
     * A bank-to-bank move arrives whole, so it is never asked about. The field appearing there
     * would suggest money can go missing between two of your own accounts.
     */
    @Test
    fun `no second amount is offered for an ordinary account`() {
        show(listOf(federal, account("3062", "Kerala Gramin Bank")))
        amountField().performTextInput("500")
        chips("Federal Bank")[0].performClick()
        chips("Kerala Gramin Bank")[1].performClick()
        compose.onNodeWithText("Reached the card", ignoreCase = true).assertDoesNotExist()
    }

    /** On a card it is offered, and it is optional — "same as above" is the ordinary case. */
    @Test
    fun `a card destination offers what actually reached it`() {
        show()
        amountField().performTextInput("500")
        chips("Federal Bank")[0].performClick()
        chips("ICICI Bank")[1].performClick()
        compose.onNodeWithText("Reached the card", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Same as above", substring = true).assertIsDisplayed()
    }

    // ── What it hands back ────────────────────────────────────────────────────────────

    /** Left blank, the same amount arrived — the common case costs nothing to say. */
    @Test
    fun `both amounts are the same when the second is left blank`() {
        val got = show()
        amountField().performTextInput("500")
        chips("Federal Bank")[0].performClick()
        chips("ICICI Bank")[1].performClick()
        save().performClick()

        assertThat(got.out).isEqualTo(500_00L)
        assertThat(got.into).isEqualTo(500_00L)
        assertThat(got.from?.accountTail).isEqualTo("4657")
        assertThat(got.to?.accountTail).isEqualTo("3008")
    }

    /**
     * The household's own case: ₹425.41 left the bank and ₹468.41 reached the card, because
     * ₹43 of it was points. Both figures travel, each against the account it moved on.
     */
    @Test
    fun `the two amounts travel separately when points paid part`() {
        val got = show()
        amountField().performTextInput("425.41")
        chips("Federal Bank")[0].performClick()
        chips("ICICI Bank")[1].performClick()
        reachedField().performTextInput("468.41")
        save().performClick()

        assertThat(got.out).isEqualTo(425_41L)
        assertThat(got.into).isEqualTo(468_41L)
    }

    // ── Cash ──────────────────────────────────────────────────────────────────────────

    /**
     * Cash is an end like any other, and has to be: an ATM withdrawal is money moving from an
     * account to a pocket, and counting it as spending charges the budget twice — once on the
     * way out and again on whatever the cash is then spent on.
     */
    @Test
    fun `cash is offered as both a source and a destination`() {
        show()
        assertThat(chips("Cash").fetchSemanticsNodes().size).isEqualTo(2)
    }

    // ── Opened from a row that already answers part of it ─────────────────────────────

    /**
     * A transaction on screen has settled two of the three answers — how much, and which account
     * it moved on. Asking again would be asking somebody to retype what they are looking at, and
     * the only question left is the one the app cannot know: where the money went.
     */
    @Test
    fun `a prefilled form needs only the other end`() {
        val got = Confirmed()
        compose.setContent {
            OursTheme {
                MoveMoneyForm(
                    accounts = listOf(federal, card),
                    onDismiss = {},
                    initialAmountPaise = 425_41L,
                    initialFromKey = "4657",
                    onConfirm = { from, to, out, into, _, _ ->
                        got.from = from; got.to = to; got.out = out; got.into = into
                    },
                )
            }
        }
        // The source is already chosen, so naming the destination is enough to complete it.
        save().assertIsNotEnabled()
        chips("ICICI Bank")[1].performClick()
        save().assertIsEnabled()
        save().performClick()

        assertThat(got.out).isEqualTo(425_41L)
        assertThat(got.from?.accountTail).isEqualTo("4657")
        assertThat(got.to?.accountTail).isEqualTo("3008")
    }

    /** A credit arrives somewhere, so the row's account is the destination rather than the source. */
    @Test
    fun `a prefilled destination leaves the source to be named`() {
        val got = Confirmed()
        compose.setContent {
            OursTheme {
                MoveMoneyForm(
                    accounts = listOf(federal, card),
                    onDismiss = {},
                    initialAmountPaise = 5_000_00L,
                    initialToKey = "3008",
                    onConfirm = { from, to, out, into, _, _ ->
                        got.from = from; got.to = to; got.out = out; got.into = into
                    },
                )
            }
        }
        chips("Federal Bank")[0].performClick()
        save().performClick()

        assertThat(got.from?.accountTail).isEqualTo("4657")
        assertThat(got.to?.accountTail).isEqualTo("3008")
    }
}
