package com.manuel.ours.ui.lock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.Notice
import com.manuel.ours.ui.components.NoticeTone
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.WordmarkStyle

/**
 * Why the biometric prompt went away, when it matters.
 *
 * @param secondsLeft counts down on a temporary lockout; null when the wait is indefinite
 *   and only the screen lock will clear it.
 */
sealed interface LockState {
    /** Locked, nothing wrong. The prompt was dismissed, or has not been answered yet. */
    data object Idle : LockState

    /**
     * `ERROR_LOCKOUT` — five failed fingerprints, biometrics off for about thirty seconds.
     * `ERROR_LOCKOUT_PERMANENT` — off until the screen lock is used, so no countdown.
     */
    data class LockedOut(val secondsLeft: Int?) : LockState
}

/**
 * The screen behind the unlock prompt: this household's statement, redacted.
 *
 * Anyone who turns the lock on sees this more often than any other screen — several times
 * a day, always before they have got what they came for. It used to spend that moment on a
 * padlock, a sentence and a button on an empty field: a composition that would suit a notes
 * app or a password manager equally well, on the one screen seen most.
 *
 * So it spends it on the ledger instead. The month is laid out exactly as it always is —
 * same rows, same hairlines, same right-hand amount column — with a bar of ink where every
 * figure and every name would be. Recognisably yours, and disclosing nothing, which is
 * precisely what a lock is.
 *
 * **The bars are invented.** Their widths are a fixed pattern in this file and are never
 * derived from real rows: a redaction whose shape follows the data leaks the shape of the
 * data. How many payments there were, and whether one of them was large, are both things
 * this screen must not say.
 */
@Composable
fun LockedScreen(
    state: LockState,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drives the unlock fade. 0 while locked, 1 once the bars should be gone. */
    revealed: Float = 0f,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Ours.surface)
            .padding(horizontal = Space.edge)
            .padding(top = Space.s4, bottom = Space.s6),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ours", style = WordmarkStyle, color = Ours.onSurface)
            LockedPill()
        }

        Spacer(Modifier.height(Space.s8))
        MicroLabel("Spent this month")
        Spacer(Modifier.height(Space.s3))
        RedactedFigure(revealed = revealed)

        Spacer(Modifier.height(Space.s6))
        TapeHeader("This month", trailing = null)
        RedactedField(
            Modifier
                .weight(1f)
                .padding(top = Space.s2),
            revealed = revealed,
        )

        // The only unredacted words on the screen, and the only accent.
        when (state) {
            LockState.Idle -> IdleFooter(onUnlock)
            is LockState.LockedOut -> LockedOutFooter(state, onUnlock)
        }
    }
}

@Composable
private fun IdleFooter(onUnlock: () -> Unit) {
    Text(
        "Ours is locked",
        style = MaterialTheme.typography.headlineMedium,
        color = Ours.onSurface,
    )
    Spacer(Modifier.height(Space.s3))
    Text(
        "Unlock with your fingerprint, face or screen lock.",
        style = MaterialTheme.typography.bodyLarge,
        color = Ours.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.s5))
    AccentButton("Unlock", onClick = onUnlock)
}

/**
 * Amber, not red. The house rule is that red marks *a figure that is wrong*, and no figure
 * here is wrong — something is merely not running yet.
 *
 * The countdown is named rather than left as "try again later", because a vague wait makes
 * people tap repeatedly and on some builds each tap extends the window. And the way in is
 * the primary button: the screen lock always works, so it is the biggest thing on screen,
 * rather than a disabled fingerprint button that repeats what the notice already said.
 */
@Composable
private fun LockedOutFooter(state: LockState.LockedOut, onUnlock: () -> Unit) {
    Notice(
        tone = NoticeTone.Warning,
        title = "Too many attempts",
        body = state.secondsLeft?.let {
            "Your fingerprint is off for $it ${if (it == 1) "second" else "seconds"}. " +
                "Your screen lock still works."
        } ?: "Your fingerprint is off until you use your screen lock.",
    )
    Spacer(Modifier.height(Space.s3))
    AccentButton("Use screen lock", onClick = onUnlock)
}

@Composable
private fun LockedPill() {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Ours.surfaceContainer)
            .padding(horizontal = Space.s3, vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OursIconView(
            icon = OursIcon.Locked,
            contentDescription = null,
            tint = Ours.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            "Locked",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Ours.onSurfaceVariant,
        )
    }
}

/** The hero figure, with its digits struck out but its rupee sign left standing. */
@Composable
private fun RedactedFigure(revealed: Float) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(
            "₹",
            style = MaterialTheme.typography.displayMedium,
            color = Ours.onSurface.copy(alpha = 1f - revealed),
        )
        listOf(34.dp, 50.dp, 34.dp).forEach { w ->
            RedactBar(width = w, height = 26.dp, revealed = revealed, strong = true)
        }
    }
}

/**
 * The field of struck-out rows, faded away downward so it reads as a page continuing
 * rather than a list that happens to stop.
 */
@Composable
private fun RedactedField(modifier: Modifier = Modifier, revealed: Float) {
    Box(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s5)) {
            ROWS.forEachIndexed { index, row ->
                // Top-down stagger: the figure people came for clears first, so the
                // animation never costs anyone a moment.
                val delay = (index * STAGGER_MS).coerceAtMost(MAX_STAGGER_MS)
                val rowReveal by animateFloatAsState(
                    targetValue = revealed,
                    animationSpec = tween(REVEAL_MS, delayMillis = delay, easing = LinearEasing),
                    label = "row$index",
                )
                RedactedRow(row, rowReveal)
            }
        }
        // Masked away at the bottom edge, in the surface colour so it is the page fading
        // and not a grey panel sitting on top of one.
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(72.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Ours.surface),
                    )
                )
        )
    }
}

@Composable
private fun RedactedRow(row: RedactRow, revealed: Float) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The category mark, which on a real row is a tinted disc.
        Box(
            Modifier
                .size(30.dp)
                .alpha(1f - revealed)
                .clip(RoundedCornerShape(999.dp))
                .background(Ours.onSurface.copy(alpha = MARK_ALPHA))
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            RedactBar(width = row.name, height = 11.dp, revealed = revealed, strong = true)
            RedactBar(width = row.caption, height = 8.dp, revealed = revealed, strong = false)
        }
        RedactBar(width = row.amount, height = 12.dp, revealed = revealed, strong = true)
    }
}

@Composable
private fun RedactBar(width: Dp, height: Dp, revealed: Float, strong: Boolean) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .alpha(1f - revealed)
            .clip(RoundedCornerShape(3.dp))
            .background(Ours.onSurface.copy(alpha = if (strong) BAR_ALPHA else BAR_ALPHA_FAINT))
    )
}

private data class RedactRow(val name: Dp, val caption: Dp, val amount: Dp)

/**
 * Invented widths, and deliberately so — see the note on [LockedScreen].
 *
 * Varied enough to read as a real statement rather than a loading skeleton, and fixed so
 * that two people looking at two locked phones cannot tell whose month was busier.
 */
private val ROWS = listOf(
    RedactRow(name = 96.dp, caption = 58.dp, amount = 62.dp),
    RedactRow(name = 70.dp, caption = 74.dp, amount = 50.dp),
    RedactRow(name = 112.dp, caption = 50.dp, amount = 72.dp),
    RedactRow(name = 86.dp, caption = 66.dp, amount = 60.dp),
    RedactRow(name = 104.dp, caption = 58.dp, amount = 48.dp),
    RedactRow(name = 78.dp, caption = 70.dp, amount = 64.dp),
    RedactRow(name = 120.dp, caption = 52.dp, amount = 70.dp),
)

private const val BAR_ALPHA = 0.16f
private const val BAR_ALPHA_FAINT = 0.09f
private const val MARK_ALPHA = 0.10f

/** 300ms, 24ms per row, capped so the last row is never left behind. */
private const val REVEAL_MS = 300
private const val STAGGER_MS = 24
private const val MAX_STAGGER_MS = 144
