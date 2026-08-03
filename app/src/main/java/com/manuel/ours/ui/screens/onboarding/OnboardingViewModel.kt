package com.manuel.ours.ui.screens.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.HouseholdRepository
import com.manuel.ours.work.SmsBackfillWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class OnboardingStep { INTRO, ACCOUNT, HOUSEHOLD, PERMISSION, BACKFILL }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.INTRO,
    val inviteSecret: String? = null,
    val backfillScanned: Int = 0,
    val backfillTotal: Int = 0,
    val backfillImported: Int = 0,
    val backfillFinished: Boolean = false,
    /** Transactions actually held, which is what the user cares about. */
    val transactionCount: Int = 0,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    application: Application,
    private val prefs: AppPrefs,
    private val householdRepository: HouseholdRepository,
    private val transactionRepository: com.manuel.ours.data.repo.TransactionRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Returning users skip straight past onboarding.
            if (prefs.onboarded.first()) {
                _uiState.value = _uiState.value.copy(
                    step = OnboardingStep.BACKFILL,
                    backfillFinished = true,
                )
            }
        }
    }

    fun advanceTo(step: OnboardingStep) {
        _uiState.value = _uiState.value.copy(step = step)
    }

    /**
     * Identity is local by design. There is no account to create and no server to
     * sign in to: a household is a shared secret the two phones agree on, and the
     * uid only needs to be unique between them.
     */
    fun signInLocally(name: String) {
        viewModelScope.launch {
            val uid = prefs.snapshot().selfUid ?: UUID.randomUUID().toString()
            prefs.setSelf(uid, name, "")
            // Immediately, not on next launch. The backfill starts before onboarding
            // asks for a name, so those rows carry a placeholder owner — leave them
            // and the app shows you as your own partner ("Both · Me · Me").
            transactionRepository.adoptLocalTransactions()
            advanceTo(OnboardingStep.HOUSEHOLD)
        }
    }

    fun createHousehold() {
        viewModelScope.launch {
            val snapshot = prefs.snapshot()
            val bundle = householdRepository.createHousehold(
                uid = snapshot.selfUid ?: UUID.randomUUID().toString(),
                name = snapshot.selfName ?: "Me",
                email = snapshot.selfEmail.orEmpty(),
            )
            _uiState.value = _uiState.value.copy(inviteSecret = bundle.inviteSecret)
        }
    }

    fun joinHousehold(code: String) {
        viewModelScope.launch {
            val snapshot = prefs.snapshot()
            householdRepository.joinHousehold(
                bundle = HouseholdRepository.InviteBundle(
                    householdId = code,
                    inviteSecret = code,
                ),
                uid = snapshot.selfUid ?: UUID.randomUUID().toString(),
                name = snapshot.selfName ?: "Me",
                email = snapshot.selfEmail.orEmpty(),
            )
            advanceTo(OnboardingStep.PERMISSION)
        }
    }

    fun onSmsPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            advanceTo(OnboardingStep.BACKFILL)
            if (!granted) {
                // Manual-entry mode: the app stays fully usable.
                _uiState.value = _uiState.value.copy(
                    backfillFinished = true,
                    transactionCount = transactionRepository.count(),
                )
                return@launch
            }
            SmsBackfillWorker.start(getApplication())
            SmsBackfillWorker.observeProgress(getApplication()).collect { progress ->
                progress ?: return@collect
                _uiState.value = _uiState.value.copy(
                    backfillScanned = progress.scanned,
                    backfillTotal = progress.total,
                    backfillImported = progress.imported,
                    backfillFinished = progress.finished,
                    transactionCount = transactionRepository.count(),
                )
            }
        }
    }

    fun finish(onFinished: () -> Unit) {
        viewModelScope.launch {
            prefs.setOnboarded(true)
            onFinished()
        }
    }
}
