package com.manuel.ours.data.sms

import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import kotlin.math.abs

/**
 * Ranks the categories a transaction is most likely to belong to, so the notification
 * can offer three buttons instead of a fifteen-item list.
 *
 * The whole value of a quick-categorize prompt rests on the first guess being right
 * most of the time. Three wrong chips are worse than no chips: the user taps one to
 * dismiss the nag, and because corrections are *learned*, a careless tap poisons the
 * rule for that merchant permanently.
 *
 * Signals, strongest first:
 *  1. What you chose for this exact merchant before — by far the best predictor.
 *  2. A learned or seeded merchant rule.
 *  3. What you usually pick for amounts of this size.
 *  4. Your overall most-used categories, as a floor.
 */
object CategoryPredictor {

    private const val EXACT_MERCHANT_WEIGHT = 12.0
    private const val RULE_WEIGHT = 8.0
    private const val SIMILAR_AMOUNT_WEIGHT = 1.5
    private const val GLOBAL_FREQUENCY_WEIGHT = 0.2

    /** Amounts within this fraction of each other count as "similar". */
    private const val AMOUNT_BAND = 0.25

    /** Shown when there is no history at all — the most common Indian daily spends. */
    private val COLD_START = listOf(Category.FOOD, Category.GROCERIES, Category.TRANSPORT)

    fun predict(
        merchant: String,
        amountPaise: Long,
        type: TxnType,
        history: List<Transaction>,
        rules: List<MerchantRuleEntity>,
        limit: Int = 3,
    ): List<Category> {
        if (type == TxnType.CREDIT) return listOf(Category.INCOME, Category.INVESTMENTS)

        val scores = mutableMapOf<Category, Double>()
        fun bump(category: Category, by: Double) {
            scores[category] = (scores[category] ?: 0.0) + by
        }

        val merchantKey = merchant.lowercase().trim()
        val relevant = history.filter { it.type == TxnType.DEBIT }

        // 1. Same merchant, previously categorised by hand or by rule.
        relevant
            .filter { it.merchant.lowercase().trim() == merchantKey && !it.needsReview }
            .forEach { bump(it.category, EXACT_MERCHANT_WEIGHT) }

        // 2. A rule whose pattern appears in the merchant string. userDefined rules
        //    sort first out of the DAO, so an earlier correction outranks a seed.
        rules.firstOrNull { it.pattern.isNotBlank() && it.pattern in merchantKey }
            ?.let { bump(Category.fromNameOrOther(it.category), RULE_WEIGHT) }

        // 3. Amounts of a similar size. A ₹40 debit is rarely rent; a ₹15,000 one
        //    rarely a chai.
        relevant
            .filter { !it.needsReview && isSimilarAmount(it.amountPaise, amountPaise) }
            .forEach { bump(it.category, SIMILAR_AMOUNT_WEIGHT) }

        // 4. Your habits overall, as a tiebreak rather than a real signal.
        relevant
            .filter { !it.needsReview }
            .groupingBy { it.category }
            .eachCount()
            .forEach { (category, count) -> bump(category, count * GLOBAL_FREQUENCY_WEIGHT) }

        // Never suggest a bucket that means "I don't know" or that would quietly drop
        // the transaction out of the spending total.
        val ranked = scores.entries
            .filter { it.key != Category.OTHER && it.key != Category.INCOME }
            .sortedWith(compareByDescending<Map.Entry<Category, Double>> { it.value }
                .thenBy { it.key.ordinal })
            .map { it.key }

        return (ranked + COLD_START + Category.OTHER).distinct().take(limit)
    }

    private fun isSimilarAmount(a: Long, b: Long): Boolean {
        if (a == 0L || b == 0L) return false
        val larger = maxOf(a, b).toDouble()
        return abs(a - b) / larger <= AMOUNT_BAND
    }
}
