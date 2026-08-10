package com.manuel.ours.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import com.manuel.ours.ui.components.TransactionEntry
import com.manuel.ours.ui.theme.OursTheme
import com.manuel.ours.BuildConfig
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The row that draws every transaction in the app.
 *
 * The first test of any kind over `ui/`, which is 17,000 lines and had none — the instrumented
 * source set was empty, so the artifacts for this were declared and never used. Every defect
 * found in the last week's work was found by taking a screenshot and looking at it, which is not
 * a process that scales and not one that runs on a build server.
 *
 * This entry is the right place to start because it is the app's most-repeated surface: a
 * statement of several hundred rows is several hundred of these, and anything it gets wrong it
 * gets wrong everywhere at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class StatementEntryTest {

    private val composeRule = createComposeRule()

    // Order matters: the assumption has to be evaluated before the compose rule tries to launch
    // an activity. See [ComposeOnDebugOnly] — as an `@Before` this silently did nothing.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeOnDebugOnly()).around(composeRule)

    private val compose get() = composeRule


    /**
     * A caption fragment, matched without regard to case.
     *
     * `MicroLabel` renders `text.uppercase()`, so the semantics tree holds "GROCERIES" where the
     * code said "Groceries". Asserting the cased form silently fails and reads like the caption
     * is missing rather than like the test is wrong.
     */
    private fun caption(text: String) =
        compose.onNodeWithText(text, substring = true, ignoreCase = true)

    private fun txn(
        merchant: String = "Keecheril St",
        paise: Long = 450_75L,
        category: Category = Category.FOOD,
        needsReview: Boolean = false,
        type: TxnType = TxnType.DEBIT,
    ) = Transaction(
        id = "t1",
        amountPaise = paise,
        type = type,
        merchant = merchant,
        category = category,
        // A real clock time, so the caption prints one. Midnight is the parser's marker for a
        // date-only message and is deliberately rendered without a time at all.
        occurredAt = 1_800_000_000_000L,
        accountTail = "3062",
        bank = "Kerala Gramin Bank",
        ownerUid = "manuel",
        ownerName = "Manuel Correya",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        needsReview = needsReview,
    )

    private fun show(txn: Transaction, showOwner: Boolean = false) {
        compose.setContent {
            OursTheme { TransactionEntry(txn = txn, showOwner = showOwner) }
        }
    }

    /** The two things a row exists to say: who, and how much. */
    @Test
    fun `a row shows its payee and its amount`() {
        show(txn())
        compose.onNodeWithText("Keecheril St").assertIsDisplayed()
        compose.onNodeWithText("450.75").assertIsDisplayed()
    }

    /**
     * No rupee sign on a row. The amounts share one right-hand column and the column is the
     * unit — repeating ₹ on every line is what the design brief specifically rules out, and it
     * is the sort of thing that creeps back in unnoticed.
     */
    @Test
    fun `a row carries no rupee sign of its own`() {
        show(txn())
        compose.onNodeWithText("₹450.75").assertDoesNotExist()
        caption("₹").assertDoesNotExist()
    }

    /** Paise are shown here. Only totals and headlines round, and a row is neither. */
    @Test
    fun `a row shows paise rather than rounding`() {
        show(txn(paise = 1_234_56L))
        compose.onNodeWithText("1,234.56").assertIsDisplayed()
    }

    /** The category names itself in the caption, since the mark alone cannot say it. */
    @Test
    fun `the caption names the category`() {
        show(txn(category = Category.GROCERIES))
        caption("Groceries").assertIsDisplayed()
    }

    /**
     * A row nobody has confirmed says so, and says it instead of the category.
     *
     * Both states read the same way to a person — the parser was unsure, or it landed in Other —
     * and the caption has to agree with the Untagged filter or the chip counts stop adding up.
     */
    @Test
    fun `an unsure row says Untagged instead of its category`() {
        show(txn(category = Category.FOOD, needsReview = true))
        caption("Untagged").assertIsDisplayed()
        caption("Food").assertDoesNotExist()
    }

    /** An untagged row by the other route: no category was ever matched. */
    @Test
    fun `a row in Other also reads as Untagged`() {
        show(txn(category = Category.OTHER))
        caption("Untagged").assertIsDisplayed()
    }

    /**
     * With a name to fit, the category word goes rather than the name.
     *
     * The caption is one line at 11sp — about 25 characters — so "Groceries · 11:20 am · Anu"
     * would clip exactly the owner suffix it was added for. First names only, for the same
     * reason.
     */
    @Test
    fun `showing the owner drops the category and uses a first name`() {
        show(txn(category = Category.GROCERIES), showOwner = true)
        caption("Manuel").assertIsDisplayed()
        caption("Correya").assertDoesNotExist()
        caption("Groceries").assertDoesNotExist()
    }

    /**
     * A move's statement line, which is the whole of how a transfer reads on the statement.
     *
     * Both legs carry it, so the row says where the money went whichever half is being looked
     * at — see `moveMoney`.
     */
    @Test
    fun `a move leg reads from and to`() {
        show(
            txn(
                merchant = "Kerala Gramin ···3062 → ICICI ···3008",
                category = Category.SELF_TRANSFER,
            )
        )
        compose.onNodeWithText("Kerala Gramin ···3062 → ICICI ···3008").assertIsDisplayed()
        caption("Ours").assertIsDisplayed()
    }
}
