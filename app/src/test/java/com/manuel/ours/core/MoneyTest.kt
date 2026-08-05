package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoneyTest {

    /**
     * The statement rows print paise, so a total above them has to as well.
     *
     * `whole` floors, which is right for a budget or a spendable balance and wrong for
     * a sum: a day of 450.75 and 1250.50 headed by "₹1,701" sits over a column that
     * visibly adds to 1,701.25, and the reader can watch the arithmetic fail.
     */
    @Test
    fun `exact keeps the paise a floored total would drop`() {
        assertThat(Money.exact(1_70_125)).isEqualTo("₹1,701.25")
        assertThat(Money.whole(1_70_125)).isEqualTo("₹1,701")
        // Two places even when there is no fraction, so the decimal point stays put
        // down the column.
        assertThat(Money.exact(45_000)).isEqualTo("₹450.00")
        assertThat(Money.exact(0)).isEqualTo("₹0.00")
        assertThat(Money.exact(-45_075)).isEqualTo("-₹450.75")
        // The worst case a floored total can understate a sum by.
        assertThat(Money.exact(99)).isEqualTo("₹0.99")
        assertThat(Money.whole(99)).isEqualTo("₹0")
    }

    @Test
    fun `indian grouping uses lakh and crore not thousands`() {
        assertThat(Money.groupIndian(100)).isEqualTo("100")
        assertThat(Money.groupIndian(1_000)).isEqualTo("1,000")
        assertThat(Money.groupIndian(10_000)).isEqualTo("10,000")
        assertThat(Money.groupIndian(1_00_000)).isEqualTo("1,00,000")
        assertThat(Money.groupIndian(12_34_567)).isEqualTo("12,34,567")
        assertThat(Money.groupIndian(1_23_45_678)).isEqualTo("1,23,45,678")
    }

    @Test
    fun `format renders whole rupees without decimals`() {
        assertThat(Money.format(4_23_80_000)).isEqualTo("₹4,23,800")
        assertThat(Money.format(0)).isEqualTo("₹0")
    }

    @Test
    fun `format keeps paise when they are non-zero`() {
        assertThat(Money.format(45_050)).isEqualTo("₹450.50")
        assertThat(Money.format(1_00)).isEqualTo("₹1")
    }

    @Test
    fun `negative amounts keep the sign outside the symbol`() {
        assertThat(Money.format(-1_23_400)).isEqualTo("-₹1,234")
    }

    @Test
    fun `compact format uses K L and Cr`() {
        assertThat(Money.formatCompact(45_000)).isEqualTo("₹450")
        assertThat(Money.formatCompact(42_38_000)).isEqualTo("₹42.4K")
        assertThat(Money.formatCompact(12_00_000_00)).isEqualTo("₹12L")
        assertThat(Money.formatCompact(1_00_00_000_00)).isEqualTo("₹1Cr")
    }

    @Test
    fun `parses the amount shapes that appear in bank sms`() {
        assertThat(Money.parseToPaise("1,234.56")).isEqualTo(1_23_456)
        assertThat(Money.parseToPaise("1234")).isEqualTo(1_23_400)
        assertThat(Money.parseToPaise("1,23,456")).isEqualTo(1_23_45_600)
        assertThat(Money.parseToPaise("450.00")).isEqualTo(45_000)
        assertThat(Money.parseToPaise("1.2K")).isEqualTo(1_20_000)
    }

    @Test
    fun `rejects text that is not an amount`() {
        assertThat(Money.parseToPaise("abc")).isNull()
        assertThat(Money.parseToPaise("")).isNull()
        assertThat(Money.parseToPaise("1.2.3")).isNull()
    }

    @Test
    fun `paise arithmetic does not drift`() {
        // The reason amounts are Long paise and never Double: summing 0.1-rupee
        // values as doubles accumulates error that shows up as a rupee or two
        // missing from the monthly total.
        var total = 0L
        repeat(10_000) { total += 10 } // 10 paise each
        assertThat(total).isEqualTo(1_00_000)
        assertThat(Money.format(total)).isEqualTo("₹1,000")
    }

    @Test
    fun `bare drops the rupee mark but keeps Indian grouping`() {
        // The statement column carries the unit, so the mark must not be repeated on
        // every row — but the grouping still has to be lakh/crore, not thousands.
        assertThat(Money.bare(21_979_00)).isEqualTo("21,979")
        assertThat(Money.bare(1_23_45_600)).isEqualTo("1,23,456")
        assertThat(Money.bare(15_100)).isEqualTo("151")
        assertThat(Money.bare(0)).isEqualTo("0")
    }

    @Test
    fun `bare shows paise only when there are any`() {
        assertThat(Money.bare(2_358_19)).isEqualTo("2,358.19")
        assertThat(Money.bare(2_358_00)).isEqualTo("2,358")
        assertThat(Money.bare(2_358_00, withDecimals = true)).isEqualTo("2,358.00")
        // A stray single paise must not render as ".1" and break column alignment.
        assertThat(Money.bare(2_358_01)).isEqualTo("2,358.01")
    }

    @Test
    fun `bare keeps the sign on the outside`() {
        // A refund in the amount column reads as "-1,200", never "1,-200".
        assertThat(Money.bare(-1_200_00)).isEqualTo("-1,200")
        assertThat(Money.bare(-15_150)).isEqualTo("-151.50")
    }
}
