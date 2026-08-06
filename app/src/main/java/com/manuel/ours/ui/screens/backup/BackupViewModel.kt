package com.manuel.ours.ui.screens.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.backup.BackupFile
import com.manuel.ours.data.backup.BackupManager
import com.manuel.ours.data.backup.BackupRead
import com.manuel.ours.data.db.TransactionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backups: BackupManager,
    txnDao: TransactionDao,
) : ViewModel() {

    /** How many entries a backup would carry, so the button is not a leap of faith. */
    val entryCount: StateFlow<Int> = txnDao.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /**
     * A file that has been read and understood but not yet applied.
     *
     * Restoring is confirmed rather than immediate. It is the one action here that
     * changes data, and the person doing it has usually just lost some — being told what
     * the file holds before it is opened is the difference between a restore and a leap.
     */
    private val _pending = MutableStateFlow<BackupFile?>(null)
    val pending: StateFlow<BackupFile?> = _pending

    fun backUp() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = null
            runCatching { backups.shareBackup() }
                .onSuccess { _status.value = "Saved ${it.name}" }
                .onFailure { _status.value = "Could not write the backup: ${it.message}" }
            _busy.value = false
        }
    }

    fun examine(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = null
            when (val read = backups.read(uri)) {
                is BackupRead.Ok -> _pending.value = read.file
                is BackupRead.Unreadable ->
                    _status.value = "That file could not be read — ${read.detail}"
                is BackupRead.TooNew ->
                    _status.value = "That backup was written by a newer version of Ours " +
                        "(format ${read.fileVersion}, this build reads ${read.supported}). " +
                        "Update this phone first."
            }
            _busy.value = false
        }
    }

    fun cancelPending() {
        _pending.value = null
    }

    fun confirmRestore() {
        val file = _pending.value ?: return
        viewModelScope.launch {
            _busy.value = true
            _pending.value = null
            runCatching { backups.restore(file) }
                .onSuccess { report ->
                    _status.value = buildString {
                        append(report.plan.summaryLine())
                        val extras = buildList {
                            if (report.rules > 0) add("${report.rules} shared rules")
                            if (report.merchantRules > 0) add("${report.merchantRules} payee rules")
                            if (report.budgets > 0) add("${report.budgets} budgets")
                            if (report.members > 0) add("${report.members} household members")
                        }
                        if (extras.isNotEmpty()) append(" Also ${extras.joinToString(", ")}.")
                        if (report.plan.reattributed > 0) {
                            append(" ${report.plan.reattributed} of them were re-pointed at this phone.")
                        }
                        if (report.trackingStartApplied) {
                            append(" The tracking start date came across too.")
                        }
                        // Only when something was actually written. Telling somebody to
                        // sync a restore that changed nothing sends them to press a
                        // button that has no work to do.
                        if (!report.plan.changedNothing) {
                            append(" Sync to send this to the other phone.")
                        }
                    }
                }
                .onFailure { _status.value = "The restore stopped: ${it.message}" }
            _busy.value = false
        }
    }
}
