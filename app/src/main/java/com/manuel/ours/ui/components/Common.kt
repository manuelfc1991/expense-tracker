package com.manuel.ours.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.manuel.ours.core.Money

/**
 * Counts up to the target rather than snapping.
 *
 * On the one very large figure this is the single highest-impact bit of motion in the
 * app — it makes the number feel *computed* rather than merely displayed. It is used
 * exactly once per screen, on the headline, for the same reason there is one accent.
 */
@Composable
fun AnimatedAmount(
    paise: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = Color.Unspecified,
    compact: Boolean = false,
    durationMillis: Int = 900,
) {
    var displayed by remember { mutableLongStateOf(0L) }
    val animatable = remember { Animatable(0f) }

    LaunchedEffect(paise) {
        val from = displayed
        animatable.snapTo(0f)
        animatable.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing)) {
            displayed = (from + (paise - from) * value).toLong()
        }
        displayed = paise
    }

    Text(
        // Whole rupees while it counts, the real figure once it lands.
        //
        // Paise all the way up made the count-up flicker through two extra digits the
        // whole journey, which is why this was floored. But the sole caller is the
        // month's spend, sitting directly above a day total and a column of rows that
        // now print paise — floored, it was the one number on the page that disagreed
        // with the ones under it. Settling on the exact figure keeps the animation
        // quiet and the arithmetic honest.
        text = when {
            compact -> Money.formatCompact(displayed)
            displayed == paise -> Money.exact(displayed)
            else -> Money.whole(displayed)
        },
        style = style,
        color = color,
        modifier = modifier,
        maxLines = 1,
    )
}
