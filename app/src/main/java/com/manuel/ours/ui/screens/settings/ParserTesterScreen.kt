package com.manuel.ours.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.core.Money
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.ValueTextStyle

/**
 * Paste an SMS, see exactly what the parser makes of it — including *why* it was
 * rejected. When a transaction goes missing this is the difference between a
 * five-second diagnosis and an unfalsifiable "the app is broken".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParserTesterScreen(onBack: () -> Unit) {
    var sender by rememberSaveable { mutableStateOf("AD-HDFCBK") }
    var body by rememberSaveable { mutableStateOf("") }
    val parser = remember { SmsParser() }

    val result = remember(sender, body) {
        if (body.isBlank()) null
        else parser.parse(sender, body, System.currentTimeMillis())
    }

    Scaffold(
        // The whole point of this screen is the result card under the field. With
        // enableEdgeToEdge the window does not shrink, so after pasting an SMS into the
        // multi-line body the answer sat under the keyboard with no way to scroll to it.
        modifier = Modifier.imePadding(),
            // contentWindowInsets = WindowInsets(0): the NavHost already sits inside the
            // outer Scaffold's padding, so consuming system-bar insets again inset every
            // one of these screens twice — most visibly the full-bleed QR viewfinder.
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),containerColor = Ours.surface) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OursTopBar(title = "Parser", onBack = onBack)

            MicroLabel("Sender ID")
            PlainField(sender, { sender = it }, "HDFCBK", singleLine = true)

            MicroLabel("Message body")
            PlainField(body, { body = it }, "Rs.451.00 debited from a/c XX1234…", minLines = 5)

            when (result) {
                null -> Text(
                    text = "Paste a bank SMS above to see how it parses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )

                is SmsParser.Result.Expense -> ResultCard(
                    title = "Parsed as an expense",
                    color = Ours.success,
                ) {
                    val txn = result.txn
                    Field("Amount", Money.format(txn.amountPaise, withDecimals = true))
                    Field("Type", txn.type.name)
                    Field("Merchant", txn.merchant ?: "— (will be flagged for review)")
                    Field("Bank", txn.bank)
                    Field("Account", txn.accountTail?.let { "•••• $it" } ?: "—")
                    Field("Reference", txn.refNo ?: "—")
                    Field("Balance", txn.balancePaise?.let { Money.format(it) } ?: "—")
                }

                is SmsParser.Result.BillReminder -> ResultCard(
                    title = "Parsed as a bill reminder",
                    color = Ours.warning,
                ) {
                    Field("Amount due", result.amountPaise?.let { Money.format(it) } ?: "—")
                    Field("Bank", result.bank)
                    Text(
                        text = "Reminders are kept separate from expenses — this is money " +
                            "you owe, not money you've spent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceVariant,
                    )
                }

                is SmsParser.Result.Unrecognised -> ResultCard(
                    title = "Held for you to confirm",
                    color = Ours.warning,
                ) {
                    Field("Sender", result.header)
                    Field(
                        "Amount",
                        result.amountPaise?.let { Money.format(it, withDecimals = true) } ?: "—",
                    )
                    Field("Type", result.type?.name ?: "—")
                    Text(
                        text = "This reads like a payment, but the sender is not a bank we " +
                            "know and the message does not name one. It waits under " +
                            "Possible payments and counts towards nothing until you say " +
                            "what it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceVariant,
                    )
                }

                is SmsParser.Result.Ignored -> ResultCard(
                    title = "Ignored",
                    color = Ours.error,
                ) {
                    Field("Reason", result.reason.name.replace('_', ' ').lowercase())
                    Text(
                        text = explain(result.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    color: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(13.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MicroLabel(title, color = color)
        content()
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        MicroLabel(label)
        Text(
            text = value ?: "—",
            style = ValueTextStyle.copy(fontSize = 12.sp),
            color = Ours.onSurface,
        )
    }
}

/** Bare multi-line field; the parser tester is the one screen that is all input. */
@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ours.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceMuted,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = LocalTextStyle.current
                .merge(MaterialTheme.typography.bodySmall)
                .copy(color = Ours.onSurface),
            cursorBrush = SolidColor(Ours.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun explain(reason: SmsParser.Reason): String = when (reason) {
    SmsParser.Reason.UNKNOWN_SENDER ->
        "The sender isn't a bank we recognise and the message doesn't name one, or " +
            "it's a personal phone number. Personal numbers are never parsed."
    SmsParser.Reason.BEFORE_SENDER_START ->
        "This parses fine — it's just older than the date we started counting this " +
            "account from, so it's left out of the totals on purpose."
    SmsParser.Reason.OTP ->
        "This looks like a one-time password. OTPs contain amounts too, which is " +
            "exactly why they're checked first."
    SmsParser.Reason.PROMOTIONAL -> "This reads as marketing rather than a settled transaction."
    SmsParser.Reason.FAILED_TRANSACTION ->
        "The transaction failed, was declined or was reversed — no money moved."
    SmsParser.Reason.BALANCE_ENQUIRY_ONLY ->
        "This only reports a balance. There's no transaction verb, so nothing was spent."
    SmsParser.Reason.NO_AMOUNT -> "No amount could be found in the message."
    SmsParser.Reason.NO_TRANSACTION_VERB ->
        "No debit or credit wording found — nothing indicates money actually moved."
    SmsParser.Reason.BILL_REMINDER -> "This is an upcoming bill, not a completed payment."
    SmsParser.Reason.NOT_A_TRANSACTION ->
        "A statement, credit-limit change or EMI conversion. It quotes a real amount, " +
            "but no money moved — and an EMI conversion just restates a purchase that " +
            "is already recorded."
}
