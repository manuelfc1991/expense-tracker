package com.manuel.ours.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Amounts are stored as **paise** (Long) everywhere. Never use Double for money —
 * 0.1 + 0.2 != 0.3 and a rupee drifts away over a few thousand transactions.
 */
object Money {

    /** "₹1,23,456" — Indian lakh/crore grouping, not Western thousands. */
    fun format(paise: Long, withDecimals: Boolean = false): String {
        val negative = paise < 0
        val abs = kotlin.math.abs(paise)
        val rupees = abs / 100
        val fraction = abs % 100

        val grouped = groupIndian(rupees)
        val body = if (withDecimals || fraction != 0L) {
            "$grouped.${fraction.toString().padStart(2, '0')}"
        } else {
            grouped
        }
        return if (negative) "-₹$body" else "₹$body"
    }

    /**
     * "₹1,23,456" — rounded down to whole rupees, never showing paise.
     *
     * Every figure the UI presents as a total, subtotal or headline uses this. Paise
     * belong on a single transaction you are reconciling against a bank statement; on a
     * month total they are two digits of noise, and in a column of totals they are
     * worse than noise, because some rows have them and some don't and the decimal
     * points stop lining up.
     */
    fun whole(paise: Long): String = format(paise - paise % 100)

    /**
     * "1,23,456" — the same grouping with no currency mark.
     *
     * Every amount in a statement list shares one right-hand column, and once they are
     * aligned the column itself says what the unit is. Repeating ₹ on all forty rows
     * only adds a ragged left edge to the one thing that needs to stay flush.
     */
    fun bare(paise: Long, withDecimals: Boolean = false): String {
        val negative = paise < 0
        val abs = kotlin.math.abs(paise)
        val grouped = groupIndian(abs / 100)
        val fraction = abs % 100
        val body = if (withDecimals || fraction != 0L) {
            "$grouped.${fraction.toString().padStart(2, '0')}"
        } else {
            grouped
        }
        return if (negative) "-$body" else body
    }

    /** "₹1.2L", "₹42.4K", "₹1.05Cr" — for hero cards and chart axes where space is tight. */
    fun formatCompact(paise: Long): String {
        val negative = paise < 0
        val rupees = kotlin.math.abs(paise) / 100.0
        val s = when {
            rupees >= 1_00_00_000 -> trim(rupees / 1_00_00_000) + "Cr"
            rupees >= 1_00_000 -> trim(rupees / 1_00_000) + "L"
            rupees >= 1_000 -> trim(rupees / 1_000) + "K"
            else -> rupees.toLong().toString()
        }
        return if (negative) "-₹$s" else "₹$s"
    }

    private fun trim(v: Double): String {
        val r = BigDecimal(v).setScale(if (v < 10) 2 else 1, RoundingMode.HALF_UP)
        return r.stripTrailingZeros().toPlainString()
    }

    /**
     * Indian digit grouping: last 3 digits, then pairs.
     * 123456789 -> "12,34,56,789"
     */
    fun groupIndian(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val last3 = s.substring(s.length - 3)
        val rest = s.substring(0, s.length - 3)
        val sb = StringBuilder()
        var count = 0
        for (i in rest.length - 1 downTo 0) {
            sb.append(rest[i])
            count++
            if (count % 2 == 0 && i != 0) sb.append(',')
        }
        return sb.reverse().toString() + "," + last3
    }

    /**
     * Parses an amount as it appears in a bank SMS into paise.
     * Handles "1,234.56", "1234", "1.2K", "1,23,456.00".
     * Returns null if the text is not a plausible amount.
     */
    fun parseToPaise(raw: String): Long? {
        var text = raw.trim().replace(",", "").replace(" ", "")
        var multiplier = 1L
        when {
            text.endsWith("K", ignoreCase = true) -> {
                multiplier = 1_000L; text = text.dropLast(1)
            }
            text.endsWith("L", ignoreCase = true) -> {
                multiplier = 1_00_000L; text = text.dropLast(1)
            }
            text.endsWith("Cr", ignoreCase = true) -> {
                multiplier = 1_00_00_000L; text = text.dropLast(2)
            }
        }
        if (text.isEmpty() || !text.all { it.isDigit() || it == '.' }) return null
        if (text.count { it == '.' } > 1) return null
        return try {
            BigDecimal(text)
                .multiply(BigDecimal(multiplier))
                .multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
