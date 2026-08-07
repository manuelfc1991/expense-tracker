package com.manuel.ours.ui.screens.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.Money
import com.manuel.ours.core.OursZone
import com.manuel.ours.ui.components.AmountColumn
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space

/**
 * Messages that look like payments, from senders nobody has vouched for.
 *
 * The app's most expensive failures have all been silent: a bank registers a new TRAI
 * header, every message it sends is discarded before an amount is looked for, and nobody
 * finds out until months of money is missing. `FEDSMS` cost this household a credit;
 * `UTKSPR` cost it 251 card debits worth ₹44,037.
 *
 * The obvious fix — read anything payment-shaped — was built and measured against 2,810
 * real messages first. Ninety-nine unrecognised headers, six writing an amount with a
 * settled verb and a masked number, and **none of the six a bank**: an EPF passbook line,
 * an Amazon Pay balance, a fuel loyalty receipt, a Myntra gift card and two trading spams.
 * It read the EPF line as ₹61,989 of income.
 *
 * So the catch stays wide and the counting waits here. Nothing on this screen is in any
 * total, which the first line says outright — a figure that is nearly your spending is
 * worse than one that is plainly not.
 */
@Composable
fun PossiblePaymentsScreen(
    onBack: () -> Unit,
    viewModel: PossiblePaymentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(containerColor = Ours.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OursTopBar(
                    title = "POSSIBLE PAYMENTS",
                    onBack = onBack,
                    trailing = {
                        if (state.senders.isNotEmpty()) {
                            MicroLabel(
                                state.senders.size.toString(),
                                modifier = Modifier.padding(end = Space.s3),
                            )
                        }
                    },
                )
            }

            if (state.senders.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing waiting",
                        body = "Every sender that has messaged you about money is one you " +
                            "have already answered for.",
                        icon = OursIcon.Inbox,
                    )
                }
            } else {
                item {
                    Text(
                        "These look like payments but came from senders we do not know. " +
                            "Nothing here is in your total yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.edge),
                    )
                }
            }

            items(state.senders, key = { it.header }) { sender ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.edge),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                sender.header,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Ours.onSurface,
                            )
                            MicroLabel(
                                buildString {
                                    append(sender.messageCount)
                                    append(if (sender.messageCount == 1) " message" else " messages")
                                    append(" · first on ")
                                    append(OursZone.format(sender.firstAt, OursZone.day))
                                },
                            )
                        }
                        sender.lastAmountPaise?.let { AmountColumn(it) }
                    }

                    Text(
                        // The message itself, because the header alone tells nobody
                        // anything — "UTKSPR" is unreadable until you see what it wrote.
                        sender.sampleBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceVariant,
                        maxLines = 3,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OursChip(
                            label = "Yes, my bank",
                            selected = false,
                            icon = OursIcon.Done,
                            onClick = { viewModel.confirm(sender.header) },
                        )
                        OursChip(
                            label = "Not a payment",
                            selected = false,
                            onClick = { viewModel.dismiss(sender.header) },
                        )
                    }
                    MicroLabel(
                        "Applies to every message ${sender.header} sends, on both phones",
                    )
                    // A hairline between senders, as on the statement.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Ours.outlineVariant),
                    )
                }
            }
        }
    }
}
