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

    /**
     * What `ERROR_LOCKOUT` costs. Android does not tell us the remaining time, and the
     * platform's own figure is thirty seconds, so that is what the countdown starts from.
     */
    const val LOCKOUT_SECONDS = 30

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
    // Why the prompt went away, when it matters. `onAuthenticationError` used to swallow
    // every code identically, so five failed fingerprints left the user on a screen whose
    // only control reopened a prompt that failed instantly, with nothing saying why.
    var lockState by remember { mutableStateOf<LockState>(LockState.Idle) }

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
                    lockState = LockState.Idle
                    unlocked = true
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    prompting = false
                    // Stay locked either way — closing the app out from under the user is
                    // never the answer — but say which kind of "locked" this is.
                    //
                    // A dismissal is not a failure and must not be dressed as one, so the
                    // cancel codes fall through to the ordinary screen. Only a real
                    // lockout gets the notice, because only a lockout makes the button
                    // that is on screen unable to work.
                    lockState = when (code) {
                        BiometricPrompt.ERROR_LOCKOUT ->
                            LockState.LockedOut(secondsLeft = AppLock.LOCKOUT_SECONDS)
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            LockState.LockedOut(secondsLeft = null)
                        else -> LockState.Idle
                    }
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

    // The live countdown. Named rather than left as "try again later", because a vague
    // wait makes people tap repeatedly and on some builds each tap extends the window.
    val state = lockState
    LaunchedEffect(state) {
        val start = (state as? LockState.LockedOut)?.secondsLeft ?: return@LaunchedEffect
        for (remaining in start - 1 downTo 0) {
            kotlinx.coroutines.delay(1_000)
            lockState = if (remaining == 0) {
                LockState.Idle
            } else {
                LockState.LockedOut(secondsLeft = remaining)
            }
        }
    }

    // No unlock animation, deliberately.
    //
    // §21 of the mockup fades the bars out top-down, and the only way to show that is to
    // keep the ledger composed *underneath* the redaction while it fades. On every other
    // screen that would be a nicety; on this one it means the real figures are rendered,
    // behind a view that is on its way out, on the one screen whose entire job is that
    // they are not visible. One dropped frame or one interrupted animation and the lock
    // has leaked exactly what it exists to hide.
    //
    // So the swap is instant. `LockedScreen` still takes `revealed` and still staggers,
    // which costs nothing and leaves the transition available to anyone who can find a
    // way to do it without composing the content first.
    if (unlocked) {
        content()
    } else {
        LockedScreen(state = lockState, onUnlock = { authenticate() })
    }
}
