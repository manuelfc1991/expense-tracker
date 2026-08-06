package com.manuel.ours.ui.screens.requests

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.components.TransactionEntry
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space


/**
 * What the household has asked to remove, and what the owner decides.
 *
 * A delete is the one change nobody can inspect afterwards — an edit leaves a value to
 * disagree with, a deletion leaves nothing at all. Rows here are still counted in every
 * total until they are approved, because a request is not yet a decision and the
 * headline must not move on the strength of one.
 */
@Composable
fun DeleteRequestsScreen(
    onBack: () -> Unit,
    onTransactionClick: (String) -> Unit,
    viewModel: DeleteRequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(containerColor = Ours.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                OursTopBar(title = "DELETE REQUESTS", onBack = onBack)
            }

            if (state.requests.isEmpty()) {
                item {
                    // A tick, not the ledger's receipt: most empty states in this app are an
                    // empty month, but an approvals queue with nothing in it is the opposite —
                    // it is finished, which is what Sort already says with this icon.
                    EmptyState(
                        title = "Nothing waiting on you",
                        body = "If someone asks to remove an entry, it appears here and on Home.",
                        icon = OursIcon.Done,
                        iconTint = Ours.success,
                    )
                }
            } else {
                item {
                    Text(
                        "These stay in your totals until you decide.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.edge, vertical = 6.dp),
                    )
                }

                items(state.requests, key = { it.transaction.id }) { request ->
                    Column(Modifier.fillMaxWidth()) {
                        TapeHeader(
                            "Asked by ${request.askedBy}",
                            modifier = Modifier.padding(horizontal = Space.edge, vertical = 6.dp),
                        )
                        TransactionEntry(
                            txn = request.transaction,
                            showOwner = true,
                            divider = false,
                            onClick = { onTransactionClick(request.transaction.id) },
                            modifier = Modifier.padding(horizontal = Space.edge),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            MicroLabel(
                                "Delete it",
                                color = Ours.error,
                                modifier = Modifier.clickable {
                                    viewModel.approve(request.transaction.id)
                                },
                            )
                            MicroLabel(
                                "Keep it",
                                color = Ours.primary,
                                modifier = Modifier.clickable {
                                    viewModel.reject(request.transaction.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
