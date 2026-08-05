package com.manuel.ours.ui.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.prefs.ThemeMode
import com.manuel.ours.ui.components.CaptureSheetContent
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.AccentColor
import com.manuel.ours.ui.theme.ThemeTone
import com.manuel.ours.ui.theme.OursTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The capture prompt, over whatever you were doing.
 *
 * The in-app sheet only ever appeared if you happened to have Ours open, which is almost
 * never at the moment a payment goes through — you are in the shop, or in the UPI app you
 * just paid from. The notification covers that case but cannot rename a payee or take a
 * note, and it is gone in seconds.
 *
 * This is an activity with a transparent window rather than a true system overlay window:
 * a real WindowManager overlay would have to run its own lifecycle, handle rotation and
 * back by hand, and could not host Compose's dialogs. What it borrows from the overlay
 * world is only the permission — SYSTEM_ALERT_WINDOW is what Android accepts as consent
 * to start an activity from the background at all.
 */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val txnId = intent?.getStringExtra(EXTRA_TXN_ID)
        if (txnId.isNullOrBlank()) {
            finish()
            return
        }

        // Turn the screen on and show over the lock screen: a payment usually happens
        // with the phone in hand but the screen off between the tap and the receipt.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            val theme by prefs.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val tone by prefs.themeTone.collectAsStateWithLifecycle(initialValue = ThemeTone.CRISP)
            val accent by prefs.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.BLUE)
            OursTheme(themeMode = theme, tone = tone, accent = accent) {
                CaptureOverlay(txnId = txnId, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_TXN_ID = "extra_txn_id"

        fun intent(context: Context, txnId: String): Intent =
            Intent(context, CaptureActivity::class.java).apply {
                putExtra(EXTRA_TXN_ID, txnId)
                // NEW_TASK because the caller is a broadcast receiver with no task of its
                // own; CLEAR_TOP so a second payment replaces the prompt rather than
                // stacking a pile of them behind the first.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        /**
         * Whether the popup can actually be shown right now.
         *
         * Android grants no runtime dialog for this — the user has to find it in system
         * settings — so it can be revoked at any time and the answer has to be asked
         * again at every payment rather than remembered.
         */
        fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}

@androidx.compose.runtime.Composable
private fun CaptureOverlay(
    txnId: String,
    onClose: () -> Unit,
    viewModel: CaptureViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by remember(txnId) { viewModel.observe(txnId) }
        .collectAsStateWithLifecycle(initialValue = null)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // Tapping the dimmed area behind the sheet dismisses it, the way a bottom
            // sheet does. No ripple: this is empty space, not a control.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val current = state ?: return@Box
        Surface(
            color = Ours.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier
                .fillMaxWidth()
                // Swallows taps so they do not fall through to the scrim behind.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Box(Modifier.navigationBarsPadding().padding(top = 16.dp)) {
                CaptureSheetContent(
                    txn = current.txn,
                    suggestions = current.suggestions,
                    onDismiss = onClose,
                    onCategorize = { viewModel.categorize(txnId, it) },
                    onRename = { name, remember ->
                        viewModel.rename(txnId, name, current.txn.counterpartyTail, remember)
                    },
                    onNote = { viewModel.setNote(txnId, it) },
                )
            }
        }
    }
}
