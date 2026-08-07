package com.manuel.ours.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.OursZone
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


/**
 * The only answer in this app to "the phone is gone".
 *
 * SMS backfill rebuilds what the banks sent. It cannot rebuild a manual entry, a renamed
 * payee or a category somebody fixed by hand, and the sheet does not hold them either —
 * it carries sync events, and a re-created sheet starts empty. Those corrections live in
 * one database on one handset until this screen is used.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val count by viewModel.entryCount.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()

    // OpenDocument rather than GetContent: it returns a durable, readable uri from any
    // provider the phone has, including Drive and Files, which is where a backup that
    // survived the handset actually lives.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::examine) }

    Scaffold(containerColor = Ours.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                OursTopBar(title = "BACKUP", onBack = onBack)
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TapeHeader("SAVE A COPY", trailing = if (count > 0) "$count entries" else null)
                    Note(
                        "Everything this phone holds, in one file: every expense, the payees " +
                            "you have renamed, the categories you have corrected, your budgets " +
                            "and who is in the household."
                    )
                    Note(
                        "Rebuilding from your inbox brings back what the banks sent. It cannot " +
                            "bring back anything typed or fixed by hand, and the sheet does not " +
                            "keep those either. This file is the only thing that does.",
                        tone = Ours.onSurfaceMuted,
                    )
                    AccentButton(
                        label = if (busy) "Working…" else "Back up everything",
                        onClick = viewModel::backUp,
                        enabled = !busy && count > 0,
                    )
                    Note(
                        "It is not encrypted, and it includes the original bank messages — " +
                            "account tails and balances included. Keep it somewhere you would " +
                            "keep a statement.",
                        tone = Ours.warning,
                    )
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TapeHeader("RESTORE")
                    Note(
                        "Opens a backup and puts back what is missing. It only ever adds: " +
                            "nothing already here is deleted, and an entry you have since " +
                            "corrected on this phone keeps your correction."
                    )
                    GhostButton(
                        label = "Restore from a file",
                        onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                    Note(
                        "Safe to run twice — the second time will tell you it found nothing new.",
                        tone = Ours.onSurfaceMuted,
                    )
                }
            }

            status?.let { line ->
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge)) {
                        Note(line, tone = Ours.onSurface)
                    }
                }
            }
        }
    }

    pending?.let { file ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPending,
            containerColor = Ours.surfaceContainer,
            title = { Text("Restore this backup?", color = Ours.onSurface) },
            text = {
                Text(
                    buildString {
                        append("Taken ${readableDate(file.createdAt)}")
                        if (file.appVersionName.isNotBlank()) append(" on Ours ${file.appVersionName}")
                        append(". ")
                        append(
                            if (file.transactions.size == 1) "1 expense"
                            else "${file.transactions.size} expenses"
                        )
                        val extras = buildList {
                            fun count(n: Int, one: String, many: String) =
                                if (n == 1) "1 $one" else "$n $many"
                            if (file.sharedRules.isNotEmpty()) {
                                add(count(file.sharedRules.size, "shared rule", "shared rules"))
                            }
                            if (file.merchantRules.isNotEmpty()) {
                                add(count(file.merchantRules.size, "payee rule", "payee rules"))
                            }
                            // "1 budgets" reached a real screen. The household has one
                            // overall cap and usually nothing else, so the singular is
                            // the common case here, not the edge one.
                            if (file.budgets.isNotEmpty()) {
                                add(count(file.budgets.size, "budget", "budgets"))
                            }
                        }
                        if (extras.isNotEmpty()) append(", ${extras.joinToString(", ")}")
                        append(".\n\nNothing already on this phone is removed.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) {
                    Text("Restore", color = Ours.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPending) {
                    Text("Cancel", color = Ours.onSurfaceVariant)
                }
            },
        )
    }
}

private fun readableDate(epochMillis: Long): String =
    OursZone.format(epochMillis, OursZone.dateTimeComma)

@Composable
private fun Note(text: String, tone: androidx.compose.ui.graphics.Color = Ours.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = tone)
}
