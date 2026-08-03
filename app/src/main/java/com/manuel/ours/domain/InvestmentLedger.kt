package com.manuel.ours.domain

import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MoneyFlow
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType

/**
 * Tracks what you've put into savings and investments versus what has come back out.
 *
 * **The hard limit, stated plainly:** this app only ever sees cash moving through a
 * bank account. It never sees your portfolio value. So while money is invested, a gain
 * or loss is invisible — there is no SMS that says "your fund is up 12%". Only
 * *realised* movement can be measured, at the moment money actually comes back.
 *
 * The accounting is deliberately aggregate rather than per-instrument: bank messages
 * rarely identify which fund or deposit a credit belongs to, so pretending to track
 * positions individually would be precision the data cannot support.
 *
 *     invested = deposits − withdrawals      (floored at zero)
 *     realised gain = withdrawals − deposits (only once you're past break-even)
 *
 * While withdrawals are below total deposits, everything coming back is treated as
 * return of principal — neutral, not income. That is the conservative direction: it
 * never invents earnings you haven't actually realised.
 */
object InvestmentLedger {

    data class Position(
        /** Money put in and not yet taken back out. */
        val investedPaise: Long,
        /** Cash taken out beyond everything ever put in — realised profit. */
        val realisedGainPaise: Long,
        val depositsPaise: Long,
        val withdrawalsPaise: Long,
    ) {
        val hasExited: Boolean get() = investedPaise == 0L && depositsPaise > 0
    }

    fun position(allTransactions: List<Transaction>): Position {
        val deposits = allTransactions
            .filter { it.type == TxnType.DEBIT && it.category.flow == MoneyFlow.SAVING }
            .sumOf { it.amountPaise }

        val withdrawals = allTransactions
            .filter { it.type == TxnType.CREDIT && it.category == Category.INVESTMENTS }
            .sumOf { it.amountPaise }

        return Position(
            investedPaise = (deposits - withdrawals).coerceAtLeast(0),
            realisedGainPaise = (withdrawals - deposits).coerceAtLeast(0),
            depositsPaise = deposits,
            withdrawalsPaise = withdrawals,
        )
    }

    /**
     * Splits a single withdrawal into return-of-principal and profit, given what was
     * invested beforehand. Only the profit part is income.
     */
    fun splitWithdrawal(withdrawalPaise: Long, investedBeforePaise: Long): Split {
        val principal = minOf(withdrawalPaise, investedBeforePaise.coerceAtLeast(0))
        return Split(
            principalPaise = principal,
            gainPaise = withdrawalPaise - principal,
        )
    }

    data class Split(val principalPaise: Long, val gainPaise: Long)

    /**
     * A realised loss cannot be inferred from cash movements alone: taking out less
     * than you put in is indistinguishable from a partial withdrawal. It only becomes
     * a loss once you say the position is closed, which is why this takes an explicit
     * flag rather than guessing.
     */
    fun realisedLoss(position: Position, positionClosed: Boolean): Long =
        if (positionClosed && position.withdrawalsPaise < position.depositsPaise) {
            position.depositsPaise - position.withdrawalsPaise
        } else 0L
}
