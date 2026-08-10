package com.manuel.ours.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.ui.screens.summary.WhatsLeft
import com.manuel.ours.ui.theme.OursTheme
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The panel where the three kinds of money are told apart.
 *
 * This is the screen the money model is actually *read* off, and the place its mistakes become
 * visible: a card balance presented as money to spend, a fixed deposit counted as available, a
 * total that quietly folds two of the three together. Every one of those has happened here —
 * a card was counted as spendable for a whole release, and in 7.7 an unregistered ICICI card sat
 * under *What is left* on a clean device with its debt reading as capacity.
 *
 * `PutAsideTest`, `CardConversionTest` and `AffordabilityTest` pin the same partition where it is
 * *computed*. This pins it where it is *shown*, because the two have disagreed before: the
 * exclusion was honoured by the panel and ignored by `affordability()`, which is the precise
 * shape of the bug that shipped.
 */
@RunWith(RobolectricTestRunner::class)
// Deliberately taller than a phone. Three sections of accounts run past 891dp, and
// `assertIsDisplayed` means visible — below the fold reads as absent, which would be a
// test failing for a reason that has nothing to do with the partition it is checking.
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h2400dp")
class AccountsPanelTest {

    private val composeRule = createComposeRule()

    // Order matters: the assumption has to be evaluated before the compose rule tries to launch
    // an activity. See [ComposeOnDebugOnly] — as an `@Before` this silently did nothing.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeOnDebugOnly()).around(composeRule)

    private val compose get() = composeRule


    private val at = 1_800_000_000_000L

    private fun account(
        key: String,
        bank: String,
        paise: Long?,
        isCard: Boolean = false,
        isSavings: Boolean = false,
        source: BalanceSource? = BalanceSource.BANK,
    ) = AccountBalance(
        key = key,
        accountTail = key,
        bank = bank,
        balancePaise = paise,
        asOf = paise?.let { at },
        source = paise?.let { source },
        isCard = isCard,
        isSavings = isSavings,
    )

    private fun show(vararg balances: AccountBalance) {
        compose.setContent {
            OursTheme {
                WhatsLeft(
                    balances = balances.toList(),
                    selfUid = "manuel",
                    onSet = {},
                    onAdd = {},
                )
            }
        }
    }

    private fun text(t: String) =
        compose.onNodeWithText(t, substring = true, ignoreCase = true)

    /**
     * A section heading, matched whole.
     *
     * Substring matching cannot be used for these: the cards section explains itself with
     * "Owed is not subtracted from **what is left**...", so a substring search for the heading
     * finds two nodes and the assertion fails for a reason that has nothing to do with the
     * partition. `TapeHeader` renders its label as its own `MicroLabel`, so the whole string is
     * exactly the node's text.
     */
    private fun heading(t: String) = compose.onNodeWithText(t, ignoreCase = true)

    /**
     * The three headings appear only when there is something under them, and each names a
     * different quantity. "Left" is capacity, "owed" is a bill already run up, "put aside" is
     * money held and not available.
     */
    @Test
    fun `each kind gets its own heading`() {
        show(
            account("3062", "Kerala Gramin Bank", 20_000_00L),
            account("3008", "ICICI Bank", 10_531_59L, isCard = true),
            account("0165", "Federal FD", 50_000_00L, isSavings = true),
        )
        heading("What is left").assertIsDisplayed()
        heading("Owed on cards").assertIsDisplayed()
        heading("Put aside").assertIsDisplayed()
    }

    /**
     * The one that matters most: a card's figure must not be added into what is left.
     *
     * ₹20,000 in the bank and ₹10,531.59 owed on a card is ₹20,000 to spend, not ₹30,531.59 and
     * not ₹9,468.41. Both wrong answers have plausible-looking arithmetic behind them, which is
     * exactly why this is worth asserting rather than eyeballing.
     */
    @Test
    fun `a card's debt is not added to what is left`() {
        show(
            account("3062", "Kerala Gramin Bank", 20_000_00L),
            account("3008", "ICICI Bank", 10_531_59L, isCard = true),
        )
        text("₹20,000").assertIsDisplayed()
        compose.onNodeWithText("30,531", substring = true).assertDoesNotExist()
        compose.onNodeWithText("9,468", substring = true).assertDoesNotExist()
    }

    /** Nor is money put aside, which is owned but cannot be spent. */
    @Test
    fun `money put aside is not added to what is left`() {
        show(
            account("3062", "Kerala Gramin Bank", 20_000_00L),
            account("0165", "Federal FD", 50_000_00L, isSavings = true),
        )
        text("₹20,000").assertIsDisplayed()
        compose.onNodeWithText("70,000", substring = true).assertDoesNotExist()
    }

    /**
     * An account that is somehow both is filed as a card, and appears once.
     *
     * The panel partitions on `isCard` first, so this is the answer it already gives; stating it
     * here means a later reordering of those two `partition` calls cannot change it silently.
     * Appearing twice would double the household's money on the screen it trusts most.
     */
    @Test
    fun `an account marked both card and put aside is filed as a card, once`() {
        show(account("3008", "ICICI Bank", 5_000_00L, isCard = true, isSavings = true))
        heading("Owed on cards").assertIsDisplayed()
        heading("Put aside").assertDoesNotExist()
    }

    /**
     * Unknown is never zero.
     *
     * An account nobody has recorded a balance for is counted and reported, not summed as ₹0 —
     * summing it would state that the household has less than it does, on the figure it spends
     * against.
     */
    @Test
    fun `an account with no balance is shown but not summed as zero`() {
        show(
            account("3062", "Kerala Gramin Bank", 20_000_00L),
            account("SBI", "SBI", null, source = null),
        )
        text("SBI").assertIsDisplayed()
        text("₹20,000").assertIsDisplayed()
        // An em dash where the figure would be — the account is on the screen, stating that
        // nobody has said what is in it. The sentence that counts these ("one account has no
        // balance recorded, so the real figure is higher") belongs to `SafeToSpend`, which is a
        // different composable and not this panel's job.
        compose.onNodeWithText("—", substring = true).assertIsDisplayed()
    }

    /**
     * A typed figure says whose it is. A bank-quoted one corrects itself on the next message; a
     * typed one sits there looking equally authoritative while the real balance moves underneath,
     * so the two must never be indistinguishable.
     */
    @Test
    fun `a hand-typed balance is marked as such`() {
        show(account("SBI", "SBI", 2_000_00L, source = BalanceSource.HAND))
        text("you said").assertIsDisplayed()
    }
}
