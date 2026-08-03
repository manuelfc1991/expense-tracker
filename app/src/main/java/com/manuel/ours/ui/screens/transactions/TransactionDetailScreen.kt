package com.manuel.ours.ui.screens.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.WordmarkStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EDGE = 15.dp

/**
 * One entry, opened up.
 *
 * The amount is the hero here for the same reason the month total is the hero on Home:
 * it is the thing you came to check. Everything below it is provenance — which bank,
 * which account, and the original message the parser read — because the only reason to
 * open a single row is to find out whether the app got it right.
 */
@Composable
fun TransactionDetailScreen(
    txnId: String,
    onBack: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val txn by viewModel.observe(txnId).collectAsStateWithLifecycle(initialValue = null)

    Scaffold(containerColor = Ours.ink) { padding ->
        val current = txn ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BiIconView(
                        BiIcon.Back,
                        contentDescription = "Back",
                        tint = Ours.textSecondary,
                        modifier = Modifier.size(16.dp).clickable(onClick = onBack),
                    )
                    Text("ENTRY", style = WordmarkStyle, color = Ours.text)
                }
                BiIconView(
                    BiIcon.Delete,
                    contentDescription = "Delete",
                    tint = Ours.negative,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { viewModel.delete(txnId); onBack() },
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = EDGE),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    CategoryAvatar(current.category, size = 34.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            current.merchant,
                            style = MaterialTheme.typography.titleLarge,
                            color = Ours.text,
                        )
                        MicroLabel(
                            Instant.ofEpochMilli(current.occurredAt)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a"))
                        )
                    }
                }
                // Paise shown here, unlike every list. This is the one place you are
                // reconciling against a bank statement, so the exact figure matters.
                Text(
                    text = Money.format(current.amountPaise, withDecimals = true),
                    style = MaterialTheme.typography.displayMedium,
                    color = Ours.text,
                    maxLines = 1,
                )
            }

            TapeHeader("Category", modifier = Modifier.padding(horizontal = EDGE))
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = EDGE),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Category.entries.forEach { option ->
                    OursChip(
                        label = option.label,
                        selected = current.category == option,
                        icon = BiIcon.forCategory(option),
                        onClick = { viewModel.recategorize(txnId, option) },
                    )
                }
            }

            TapeHeader("Counts as", modifier = Modifier.padding(horizontal = EDGE))
            Row(
                Modifier.padding(horizontal = EDGE),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OursChip(
                    label = "Household",
                    selected = current.splitType == SplitType.SHARED,
                    onClick = { viewModel.setSplitType(txnId, SplitType.SHARED) },
                )
                OursChip(
                    label = "Personal",
                    selected = current.splitType == SplitType.PERSONAL,
                    onClick = { viewModel.setSplitType(txnId, SplitType.PERSONAL) },
                )
            }

            TapeHeader("Where it came from", modifier = Modifier.padding(horizontal = EDGE))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = EDGE),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                DetailRow("Paid by", current.ownerName)
                current.bank?.let { DetailRow("Source", it) }
                current.accountTail?.let { DetailRow("Account", "•••• $it") }
                current.refNo?.let { DetailRow("Reference", it) }
                DetailRow("Detected from", current.source.name.lowercase())
            }

            // The raw SMS is kept locally so a mis-parse can be diagnosed. It is never
            // synced — this text exists only on the phone that received it.
            current.rawSms?.let { raw ->
                TapeHeader("Original message", modifier = Modifier.padding(horizontal = EDGE))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(Ours.surface)
                            .padding(13.dp)
                    ) {
                        Text(
                            text = raw,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Ours.textSecondary,
                        )
                    }
                    MicroLabel("Stays on this phone · never synced")
                    GhostButton(
                        label = "Report wrong parse",
                        onClick = { viewModel.flagWrongParse(txnId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            MicroLabel(label)
            Text(
                value,
                style = ValueTextStyle.copy(fontWeight = FontWeight.Medium),
                color = Ours.text,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
    }
}
