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
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.QuietEmpty
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.components.TransactionEntry
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.WordmarkStyle

private val EDGE = 15.dp

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

    Scaffold(containerColor = Ours.ink) { padding ->
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
                    Text("DELETE REQUESTS", style = WordmarkStyle, color = Ours.text)
                }
            }

            if (state.requests.isEmpty()) {
                item {
                    QuietEmpty(
                        "Nothing waiting on you",
                        // A tick, not the ledger's receipt. QuietEmpty defaults to the
                        // receipt because most empty states in this app are an empty
                        // month; an approvals queue with nothing in it is the opposite —
                        // it is finished, which is what Sort already says with this icon.
                        icon = BiIcon.Done,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            } else {
                item {
                    Text(
                        "These stay in your totals until you decide.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = Ours.textSecondary,
                        modifier = Modifier.padding(horizontal = EDGE, vertical = 6.dp),
                    )
                }

                items(state.requests, key = { it.transaction.id }) { request ->
                    Column(Modifier.fillMaxWidth()) {
                        TapeHeader(
                            "Asked by ${request.askedBy}",
                            modifier = Modifier.padding(horizontal = EDGE, vertical = 6.dp),
                        )
                        TransactionEntry(
                            txn = request.transaction,
                            showOwner = true,
                            divider = false,
                            onClick = { onTransactionClick(request.transaction.id) },
                            modifier = Modifier.padding(horizontal = EDGE),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            MicroLabel(
                                "Delete it",
                                color = Ours.negative,
                                modifier = Modifier.clickable {
                                    viewModel.approve(request.transaction.id)
                                },
                            )
                            MicroLabel(
                                "Keep it",
                                color = Ours.accent,
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
