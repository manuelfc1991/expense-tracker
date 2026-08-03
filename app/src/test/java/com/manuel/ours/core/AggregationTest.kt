package com.manuel.ours.core

import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class AggregationTest {

    private val zone = MonthlyAggregator.ZONE
    private val me = "uid-me"
    private val partner = "uid-partner"

    private fun at(day: Int, month: Int = 6, year: Int = 2024): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli() +
            12 * 60 * 60 * 1000 // midday, so timezone edges don't shift the day

    private fun txn(
        amount: Long,
        day: Int,
        category: Category = Category.FOOD,
        owner: String = me,
        type: TxnType = TxnType.DEBIT,
        merchant: String = "Swiggy",
        split: SplitType = SplitType.SHARED,
        month: Int = 6,
        ownerName: String? = null,
    ) = Transaction(
        id = "$owner-$day-$amount-$category-$merchant",
        amountPaise = amount,
        type = type,
        merchant = merchant,
        category = category,
        occurredAt = at(day, month),
        splitType = split,
        ownerUid = owner,
        ownerName = ownerName ?: if (owner == me) "Me" else "Partner",
    )

    @Test
    fun `month range covers the whole month and excludes the next`() {
        val range = MonthlyAggregator.monthRange(2024, 6)
        assertThat(range.contains(at(1))).isTrue()
        assertThat(range.contains(at(30))).isTrue()
        assertThat(range.contains(at(1, month = 7))).isFalse()
    }

    @Test
    fun `totals separate debits from credits`() {
        val txns = listOf(
            txn(50_000, 1),
            txn(30_000, 2),
            txn(85_00_000, 3, type = TxnType.CREDIT, category = Category.INCOME),
        )
        assertThat(MonthlyAggregator.totalSpent(txns)).isEqualTo(80_000)
        assertThat(MonthlyAggregator.totalReceived(txns)).isEqualTo(85_00_000)
    }

    @Test
    fun `category totals carry the previous month for deltas`() {
        val current = listOf(txn(50_000, 1), txn(30_000, 2, Category.TRANSPORT))
        val previous = listOf(txn(20_000, 1, month = 5))

        val byCategory = MonthlyAggregator.byCategory(current, previous)
        val food = byCategory.first { it.category == Category.FOOD }

        assertThat(food.totalPaise).isEqualTo(50_000)
        assertThat(food.previousPaise).isEqualTo(20_000)
        assertThat(food.deltaPaise).isEqualTo(30_000)
        assertThat(food.deltaPercent).isWithin(0.01f).of(150f)
    }

    @Test
    fun `categories are sorted by spend descending`() {
        val txns = listOf(
            txn(10_000, 1, Category.FOOD),
            txn(90_000, 2, Category.RENT),
            txn(50_000, 3, Category.SHOPPING),
        )
        assertThat(MonthlyAggregator.byCategory(txns).map { it.category })
            .containsExactly(Category.RENT, Category.SHOPPING, Category.FOOD)
            .inOrder()
    }

    @Test
    fun `by day includes zero spend days`() {
        val txns = listOf(txn(50_000, 5), txn(20_000, 5), txn(10_000, 20))
        val days = MonthlyAggregator.byDay(txns, 2024, 6)

        assertThat(days).hasSize(30) // June
        assertThat(days.first { it.dayOfMonth == 5 }.totalPaise).isEqualTo(70_000)
        assertThat(days.first { it.dayOfMonth == 6 }.totalPaise).isEqualTo(0)
    }

    @Test
    fun `top merchants groups case insensitively`() {
        val txns = listOf(
            txn(10_000, 1, merchant = "SWIGGY"),
            txn(20_000, 2, merchant = "swiggy"),
            txn(5_000, 3, merchant = "Uber"),
        )
        val top = MonthlyAggregator.topMerchants(txns)
        assertThat(top.first().totalPaise).isEqualTo(30_000)
        assertThat(top.first().txnCount).isEqualTo(2)
    }

    @Test
    fun `filter ME shows only my transactions`() {
        val txns = listOf(txn(10_000, 1, owner = me), txn(20_000, 2, owner = partner))
        val filtered = MonthlyAggregator.applyFilter(txns, MemberFilter.Person(me), me)
        assertThat(filtered).hasSize(1)
        assertThat(filtered.first().ownerUid).isEqualTo(me)
    }

    @Test
    fun `filter PARTNER shows only their transactions`() {
        val txns = listOf(txn(10_000, 1, owner = me), txn(20_000, 2, owner = partner))
        val filtered = MonthlyAggregator.applyFilter(txns, MemberFilter.Person(partner), me)
        assertThat(filtered.single().ownerUid).isEqualTo(partner)
    }

    @Test
    fun `BOTH hides the partner's personal spend but keeps mine`() {
        val txns = listOf(
            txn(10_000, 1, owner = me, split = SplitType.SHARED),
            txn(20_000, 2, owner = me, split = SplitType.PERSONAL),
            txn(30_000, 3, owner = partner, split = SplitType.SHARED),
            txn(40_000, 4, owner = partner, split = SplitType.PERSONAL),
        )
        val filtered = MonthlyAggregator.applyFilter(txns, MemberFilter.Everyone, me)

        assertThat(MonthlyAggregator.totalSpent(filtered)).isEqualTo(60_000)
        assertThat(filtered.none { it.ownerUid == partner && it.splitType == SplitType.PERSONAL })
            .isTrue()
    }

    @Test
    fun `member split adds up to the household total`() {
        val txns = listOf(
            txn(30_000, 1, owner = me),
            txn(20_000, 2, owner = me),
            txn(50_000, 3, owner = partner),
        )
        val byMember = MonthlyAggregator.byMember(txns)

        assertThat(byMember.sumOf { it.totalPaise })
            .isEqualTo(MonthlyAggregator.totalSpent(txns))
        assertThat(byMember.first().totalPaise).isEqualTo(50_000) // sorted desc
    }

    @Test
    fun `summary wires everything together`() {
        val current = listOf(
            txn(1_00_000, 5, Category.FOOD),
            txn(2_00_000, 10, Category.RENT),
            txn(50_000, 15, Category.FOOD, owner = partner),
        )
        val previous = listOf(txn(80_000, 5, Category.FOOD, month = 5))

        val summary = MonthlyAggregator.summarize(2024, 6, current, previous)

        assertThat(summary.totalSpentPaise).isEqualTo(3_50_000)
        assertThat(summary.previousMonthSpentPaise).isEqualTo(80_000)
        assertThat(summary.byMember).hasSize(2)
        assertThat(summary.biggestExpense?.amountPaise).isEqualTo(2_00_000)
        assertThat(summary.byDay).hasSize(30)
    }

    @Test
    fun `insights stay quiet when nothing meaningful changed`() {
        // 2% swing on a small base — not worth a card.
        val insights = MonthlyAggregator.buildInsights(
            categories = emptyList(),
            totalNow = 1_02_000,
            totalPrevious = 1_00_000,
        )
        assertThat(insights).isEmpty()
    }

    @Test
    fun `insights flag a large increase`() {
        val insights = MonthlyAggregator.buildInsights(
            categories = emptyList(),
            totalNow = 2_00_000,
            totalPrevious = 1_00_000,
        )
        assertThat(insights).isNotEmpty()
        assertThat(insights.first().text).contains("more than last month")
    }

    @Test
    fun `net savings is credits minus debits`() {
        val summary = MonthlyAggregator.summarize(
            2024, 6,
            listOf(
                txn(50_00_000, 1, type = TxnType.CREDIT, category = Category.INCOME),
                txn(30_00_000, 2),
            ),
            emptyList(),
        )
        assertThat(summary.netPaise).isEqualTo(20_00_000)
    }

    @Test
    fun `a household of three keeps everyone visible and counted`() {
        // Was BOTH / ME / PARTNER, which asserted a household of exactly two: "Partner"
        // meant everyone-who-is-not-me, so a wife and a child were indistinguishable
        // and neither could be looked at alone.
        val me = "uid-me"
        val wife = "uid-wife"
        val child = "uid-child"
        val txns = listOf(
            txn(500_00, day = 5, owner = me, ownerName = "Manuel"),
            txn(300_00, day = 5, owner = wife, ownerName = "Sindhu"),
            txn(200_00, day = 5, owner = child, ownerName = "Child"),
        )

        val people = MonthlyAggregator.peopleIn(txns, me)
        assertThat(people.map { it.uid }).containsExactly(me, wife, child)
        // Self first, so "Me" is always the leftmost chip.
        assertThat(people.first().isSelf).isTrue()

        // Each person is reachable on their own.
        assertThat(MonthlyAggregator.applyFilter(txns, MemberFilter.Person(child), me))
            .hasSize(1)
        assertThat(MonthlyAggregator.applyFilter(txns, MemberFilter.Person(wife), me))
            .hasSize(1)

        // And a child's shared spending counts toward the household, exactly as a
        // partner's does — that was the decision, so it is pinned here.
        val everyone = MonthlyAggregator.applyFilter(txns, MemberFilter.Everyone, me)
        assertThat(MonthlyAggregator.totalSpent(everyone)).isEqualTo(1000_00)
    }

    @Test
    fun `someone else's personal spending stays out of the household total`() {
        val me = "uid-me"
        val child = "uid-child"
        val txns = listOf(
            txn(500_00, day = 5, owner = me, ownerName = "Manuel"),
            txn(200_00, day = 5, owner = child, ownerName = "Child", split = SplitType.PERSONAL),
        )
        val everyone = MonthlyAggregator.applyFilter(txns, MemberFilter.Everyone, me)
        assertThat(MonthlyAggregator.totalSpent(everyone)).isEqualTo(500_00)

        // But it is still theirs to see when you ask for them specifically.
        assertThat(MonthlyAggregator.applyFilter(txns, MemberFilter.Person(child), me))
            .hasSize(1)
    }

    @Test
    fun `the placeholder owner never becomes a household member`() {
        // "local" is me before I had an id. Counting it as a person is what used to
        // render "Both / Me / Me".
        val me = "uid-me"
        val txns = listOf(
            txn(500_00, day = 5, owner = me, ownerName = "Manuel"),
            txn(100_00, day = 5, owner = "local", ownerName = "Me"),
        )
        assertThat(MonthlyAggregator.peopleIn(txns, me).map { it.uid }).containsExactly(me)
    }
}
