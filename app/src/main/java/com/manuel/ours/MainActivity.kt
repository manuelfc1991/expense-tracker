package com.manuel.ours

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.manuel.ours.data.prefs.AppPrefs
import androidx.fragment.app.FragmentActivity
import com.manuel.ours.data.prefs.ThemeMode
import com.manuel.ours.ui.lock.AppLockGate
import com.manuel.ours.ui.nav.OursNavHost
import com.manuel.ours.ui.nav.Routes
import com.manuel.ours.ui.theme.OursTheme
import com.manuel.ours.work.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FragmentActivity rather than ComponentActivity: BiometricPrompt requires a
 * FragmentActivity to host its dialog, and swapping the base class later would be a
 * far more invasive change than starting with it.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val openTxnId = intent?.getStringExtra(EXTRA_TXN_ID)
        // Read synchronously, before setContent. Waiting on DataStore here meant a
        // blank frame on every single launch.
        val startedOnboarded = prefs.onboardedBlocking()

        setContent {
            val theme by prefs.theme.collectAsState(initial = ThemeMode.SYSTEM)
            val appLock by prefs.appLock.collectAsState(initial = false)
            OursTheme(themeMode = theme) {
                AppLockGate(enabled = appLock) {
                    OursNavHost(
                        initialTransactionId = openTxnId,
                        startDestination = if (startedOnboarded) Routes.HOME
                        else Routes.ONBOARDING,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Foregrounding is the cheapest signal that the user is about to look at
        // numbers, so pull whatever the other phone has recorded since.
        SyncWorker.syncNow(this)
    }

    companion object {
        const val EXTRA_TXN_ID = "extra_txn_id"
    }
}
