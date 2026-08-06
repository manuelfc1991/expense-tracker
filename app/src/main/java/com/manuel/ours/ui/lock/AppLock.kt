package com.manuel.ours.ui.lock

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.theme.Ours
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Gates the app behind the phone's own unlock.
 *
 * Uses `DEVICE_CREDENTIAL` alongside biometrics rather than fingerprint alone: not
 * every phone has a sensor, sensors fail with wet or cold hands, and a lock you can
 * get permanently stuck behind is worse than no lock. The PIN is always a way in.
 *
 * Re-locks after [RELOCK_AFTER_MS] in the background. Locking on every glance away
 * makes an app people open ten times a day unusable, so a short grace period is the
 * difference between a feature that stays on and one that gets switched off.
 */
object AppLock {
    const val RELOCK_AFTER_MS = 60_000L

    fun isAvailable(context: android.content.Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS

    val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
}

@Composable
fun AppLockGate(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // Nothing to gate, or no way to authenticate — never strand the user outside
    // their own data because the hardware cannot satisfy the prompt.
    if (!enabled || activity == null || !AppLock.isAvailable(context)) {
        content()
        return
    }

    var unlocked by remember { mutableStateOf(false) }
    var prompting by remember { mutableStateOf(false) }
    var backgroundedAt by remember { mutableStateOf(0L) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val away = SystemClock.elapsedRealtime() - backgroundedAt
                    if (backgroundedAt > 0L && away > AppLock.RELOCK_AFTER_MS) unlocked = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun authenticate() {
        if (prompting) return
        prompting = true
        val prompt = BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    unlocked = true
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    prompting = false
                    // Cancelled or lockout: stay locked and show the retry screen
                    // rather than closing the app out from under the user.
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Ours")
                .setSubtitle("Your household spending is locked")
                .setAllowedAuthenticators(AppLock.ALLOWED)
                .build()
        )
    }

    LaunchedEffect(unlocked) {
        if (!unlocked) authenticate()
    }

    if (unlocked) {
        content()
    } else {
        LockedScreen(onRetry = { authenticate() })
    }
}

@Composable
private fun LockedScreen(onRetry: () -> Unit) {
    // Left-ranged and quiet, like every other screen. A centred padlock with a centred
    // heading is the one shape that says "error"; being locked is not an error.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ours.surface)
            .padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        OursIconView(
            icon = OursIcon.Locked,
            contentDescription = null,
            tint = Ours.primary,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.height(18.dp)) {}
        Text("Ours is locked", style = MaterialTheme.typography.headlineMedium, color = Ours.onSurface)
        Column(Modifier.height(10.dp)) {}
        Text(
            text = "Unlock with your fingerprint, face or screen lock.",
            style = MaterialTheme.typography.bodyLarge,
            color = Ours.onSurfaceVariant,
        )
        Column(Modifier.height(26.dp)) {}
        AccentButton("Unlock", onClick = onRetry)
    }
}
