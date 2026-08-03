package com.manuel.ours.sms

import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.data.sms.CategoryPredictor
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Three chips are the entire interface, so the first guess has to be right most of
 * the time. A wrong suggestion is worse than none: the user taps one to dismiss the
 * nag, and because corrections are learned, that careless tap sticks to the merchant
 * for good.
 */
class CategoryPredictorTest {

    private var seq = 0

    private fun txn(
        merchant: String,
        category: Category,
        rupees: Long = 300,
        needsReview: Boolean = false,
    ) = Transaction(
        id = "t${seq++}",
        amountPaise = rupees * 100,
        type = TxnType.DEBIT,
        merchant = merchant,
        category = category,
        occurredAt = 1_785_000_000_000L,
        ownerUid = "me",
        ownerName = "Me",
        needsReview = needsReview,
    )

    private fun rule(pattern: String, category: Category, userDefined: Boolean = true) =
        MerchantRuleEntity(pattern = pattern, category = category.name, userDefined = userDefined)

    private fun predict(
        merchant: String,
        rupees: Long = 300,
        history: List<Transaction> = emptyList(),
        rules: List<MerchantRuleEntity> = emptyList(),
    ) = CategoryPredictor.predict(
        merchant, rupees * 100, TxnType.DEBIT, history, rules,
    )

    @Test
    fun `what you chose last time for this merchant wins`() {
        val history = listOf(
            txn("Swiggy", Category.FOOD),
            txn("Swiggy", Category.FOOD),
            txn("Zepto", Category.GROCERIES),
        )
        assertThat(predict("Swiggy", history = history).first()).isEqualTo(Category.FOOD)
    }

    @Test
    fun `merchant history beats a seeded rule that disagrees`() {
        // The seed says Blinkit is groceries; this household files it under Food.
        // The user's own behaviour must win, or the app keeps re-suggesting the
        // thing they already rejected.
        val history = List(3) { txn("Blinkit", Category.FOOD) }
        val rules = listOf(rule("blinkit", Category.GROCERIES, userDefined = false))
        assertThat(predict("Blinkit", history = history, rules = rules).first())
            .isEqualTo(Category.FOOD)
    }

    @Test
    fun `a rule applies to a merchant never seen before`() {
        val rules = listOf(rule("apollo", Category.HEALTH))
        assertThat(predict("Apollo Pharmaci", rules = rules).first()).isEqualTo(Category.HEALTH)
    }

    @Test
    fun `matching is case insensitive`() {
        val history = List(2) { txn("RELIANCE SMART", Category.GROCERIES) }
        assertThat(predict("reliance smart", history = history).first())
            .isEqualTo(Category.GROCERIES)
    }

    @Test
    fun `similar amounts nudge the ranking`() {
        // No merchant history at all, but ₹12,000 debits have always been rent.
        val history = List(4) { txn("Landlord", Category.RENT, rupees = 12_000) }
        assertThat(predict("Unknown Shop", rupees = 12_500, history = history))
            .contains(Category.RENT)
    }

    @Test
    fun `never suggests Other`() {
        // "Other" is what the user is trying to escape — offering it as a chip makes
        // the prompt pointless.
        val history = List(5) { txn("Random", Category.OTHER) }
        assertThat(predict("Random", history = history)).doesNotContain(Category.OTHER)
    }

    @Test
    fun `never suggests Income for a debit`() {
        val history = List(5) { txn("Anything", Category.INCOME) }
        assertThat(predict("Anything", history = history)).doesNotContain(Category.INCOME)
    }

    @Test
    fun `credits offer income and investment, not food`() {
        val suggestions = CategoryPredictor.predict(
            "Employer", 85_00_000, TxnType.CREDIT, emptyList(), emptyList(),
        )
        assertThat(suggestions).contains(Category.INCOME)
        assertThat(suggestions).doesNotContain(Category.FOOD)
    }

    @Test
    fun `always returns three suggestions even with no history`() {
        val suggestions = predict("Brand New Shop")
        assertThat(suggestions).hasSize(3)
        assertThat(suggestions).containsNoDuplicates()
    }

    @Test
    fun `suggestions are always distinct`() {
        val history = listOf(
            txn("Swiggy", Category.FOOD),
            txn("Zomato", Category.FOOD),
            txn("Dominos", Category.FOOD),
        )
        val suggestions = predict("Swiggy", history = history)
        assertThat(suggestions).containsNoDuplicates()
        assertThat(suggestions).hasSize(3)
    }

    @Test
    fun `an uncategorised past transaction does not train the predictor`() {
        // Rows still awaiting review are guesses, not decisions. Learning from them
        // would let one bad auto-guess compound across every future suggestion.
        val history = List(5) { txn("Mystery", Category.SHOPPING, needsReview = true) }
        assertThat(predict("Mystery", history = history).first())
            .isNotEqualTo(Category.SHOPPING)
    }

    @Test
    fun `ranking is deterministic for equal scores`() {
        val history = listOf(txn("A", Category.FOOD), txn("B", Category.TRANSPORT))
        val first = predict("Unseen", history = history)
        repeat(20) { assertThat(predict("Unseen", history = history)).isEqualTo(first) }
    }
}
