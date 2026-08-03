package com.manuel.ours.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manuel.ours.R
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.OursMono
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.WordmarkStyle

private val EDGE = 15.dp

private data class Step(
    val title: String,
    val body: String,
    /** Shown in mono, for the exact strings that must be typed or chosen. */
    val literal: String? = null,
    val warn: String? = null,
)

private val steps = listOf(
    Step(
        title = "Make a spreadsheet",
        body = "On a computer, open sheets.google.com and create a blank spreadsheet. " +
            "Name it anything. You never have to type in it — the script fills it.",
    ),
    Step(
        title = "Open Apps Script",
        body = "In that spreadsheet: Extensions ▸ Apps Script. Delete the empty " +
            "myFunction stub it starts you with.",
    ),
    Step(
        title = "Paste the script",
        body = "Copy the script with the button below — or send it to yourself with " +
            "Share — then paste the whole thing in and save.",
    ),
    Step(
        title = "Deploy it as a web app",
        body = "Deploy ▸ New deployment ▸ (gear) ▸ Web app, then set both fields:",
        literal = "Execute as:      Me\nWho has access:  Anyone",
        warn = "\"Anyone\" is required. With anything else the phones receive a Google " +
            "login page instead of your data, and sync fails with no obvious reason.",
    ),
    Step(
        title = "Let it run",
        body = "Google will call it an unverified app, because it is — you wrote it " +
            "five seconds ago. Advanced ▸ Go to (unsafe) ▸ Allow.",
    ),
    Step(
        title = "Copy the /exec link",
        body = "Take the URL ending in /exec. The /dev one only works while you are " +
            "signed in to the editor, so it will appear to work for you and fail for her.",
        literal = "https://script.google.com/macros/s/…/exec",
    ),
    Step(
        title = "Paste it on both phones",
        body = "Settings ▸ Sheet sync ▸ paste ▸ Connect. It should say Connected, with " +
            "your spreadsheet's name. Do this on her phone too, with the same link — " +
            "that link is what makes it the same household ledger.",
    ),
)

/**
 * The Sheet setup walkthrough, in the app rather than in a document nobody can find.
 *
 * Both phones have to do this, and the person doing it second is usually not the person
 * who read the instructions. It also carries the script itself, so the setup never
 * depends on having the source repository to hand.
 */
@Composable
fun SheetSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val script = remember { context.readSyncScript() }
    var copied by remember { mutableStateOf(false) }

    Scaffold(containerColor = Ours.ink) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    Text("SHEET SETUP", style = WordmarkStyle, color = Ours.text)
                }
            }

            item {
                Text(
                    "About five minutes, once, on a computer. After this both phones see " +
                        "every expense wherever they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ours.textSecondary,
                    modifier = Modifier.padding(horizontal = EDGE),
                )
            }

            itemsIndexed(steps) { index, step ->
                StepRow(number = index + 1, step = step)
            }

            item {
                TapeHeader("The script", modifier = Modifier.padding(horizontal = EDGE))
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentButton(
                        label = if (copied) "Copied" else "Copy script",
                        icon = if (copied) BiIcon.Done else null,
                        onClick = {
                            context.copyToClipboard(script)
                            copied = true
                        },
                    )
                    GhostButton(
                        label = "Send it to my computer",
                        onClick = { context.shareScript(script) },
                    )
                    MicroLabel("${script.lines().size} lines · nothing to edit")
                }
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EDGE)
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.dp, Ours.warning.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                        .padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MicroLabel("Worth knowing", color = Ours.warning)
                    Text(
                        "That link is the only credential. Anyone who has it can read and " +
                            "change your expenses, so treat it like a password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.textSecondary,
                    )
                    Text(
                        "The sheet stores your data in plain text, including the original " +
                            "bank messages. That is the deliberate trade for a ledger you " +
                            "can open and repair yourself. Bluetooth sync is encrypted; " +
                            "this is not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.textSecondary,
                    )
                    Text(
                        "Re-deploying issues a new link and kills the old one. If you ever " +
                            "redeploy, both phones need the new link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, step: Step) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = EDGE),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The number is the ruler mark: same mono, same tracking as every other label.
        Box(Modifier.width(18.dp)) {
            Text(
                number.toString(),
                style = ValueTextStyle,
                color = Ours.accent,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.text,
            )
            Text(
                step.body,
                style = MaterialTheme.typography.bodySmall,
                color = Ours.textSecondary,
            )
            step.literal?.let {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(Ours.surface)
                        .padding(11.dp)
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = OursMono,
                        color = Ours.text,
                    )
                }
            }
            step.warn?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.warning,
                )
            }
        }
    }
}

private fun Context.readSyncScript(): String =
    resources.openRawResource(R.raw.sheet_sync_script).bufferedReader().use { it.readText() }

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Ours sync script", text))
}

private fun Context.shareScript(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Ours — Google Sheet sync script")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, "Send the script").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
