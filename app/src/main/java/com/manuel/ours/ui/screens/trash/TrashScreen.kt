package com.manuel.ours.ui.screens.trash

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.domain.Trash
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.QuietEmpty
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.components.TransactionEntry
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.WordmarkStyle

private val EDGE = 15.dp

/**
 * What has been deleted, and how long it can still be brought back.
 *
 * The window is deliberately over `deletedAt` and not over the whole tombstone table.
 * The first real household had 446 soft-deleted rows and had chosen to delete almost
 * none of them — dedupe repairs and bulk tidy-ups account for the rest. Showing those
 * would turn a recovery screen into an archive of the app's own housekeeping, and bury
 * the one entry somebody actually wants back.
 *
 * Restoring writes a fresh upsert with a higher Lamport value rather than clearing the
 * flag in place, so it also wins on the other phone. See `TransactionRepository.restore`.
 */
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    onTransactionClick: (String) -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val restored by viewModel.restored.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(restored) {
        val name = restored ?: return@LaunchedEffect
        snackbarHost.showSnackbar("$name is back", duration = SnackbarDuration.Short)
        viewModel.clearRestored()
    }

    Scaffold(
        containerColor = Ours.ink,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BiIconView(
                        BiIcon.Back,
                        contentDescription = "Back",
                        tint = Ours.textSecondary,
                        modifier = Modifier.size(16.dp).clickable(onClick = onBack),
                    )
                    Text("TRASH", style = WordmarkStyle, color = Ours.text)
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = EDGE)) {
                    TapeHeader(
                        "DELETED",
                        trailing = if (items.isEmpty()) null else "${items.size}",
                    )
                }
            }

            if (items.isEmpty()) {
                item {
                    QuietEmpty(
                        "Nothing deleted in the last ${Trash.WINDOW_DAYS} days.",
                        modifier = Modifier.padding(horizontal = EDGE, vertical = 28.dp),
                    )
                }
            }

            items(items, key = { it.id }) { txn ->
                Column(Modifier.fillMaxWidth()) {
                    TransactionEntry(
                        txn = txn,
                        showOwner = true,
                        onClick = { onTransactionClick(txn.id) },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = txn.deletedAt?.let { Trash.expiryLabel(it, viewModel.now) }
                                .orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ours.textLabel,
                        )
                        Text(
                            text = "Put back",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ours.accent,
                            modifier = Modifier
                                .clickable { viewModel.restore(txn) }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            if (items.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 14.dp)) {
                        Text(
                            "Entries leave this list ${Trash.WINDOW_DAYS} days after they " +
                                "were deleted, and cannot be put back after that. They are " +
                                "still in a backup taken before then.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ours.textLabel,
                        )
                    }
                }
            }
        }
    }
}
