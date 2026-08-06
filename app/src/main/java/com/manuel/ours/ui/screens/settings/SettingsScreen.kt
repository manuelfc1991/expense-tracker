package com.manuel.ours.ui.screens.settings

import android.Manifest
import androidx.activity.compose.BackHandler
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.OursZone
import com.manuel.ours.data.prefs.IngestSource
import com.manuel.ours.data.prefs.ThemeMode
import com.manuel.ours.data.sync.NearbyTransport
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.PillTone
import com.manuel.ours.ui.components.StatePill
import com.manuel.ours.ui.components.TapeHeader
import androidx.compose.foundation.shape.CircleShape
import com.manuel.ours.ui.theme.AccentColor
import com.manuel.ours.ui.theme.ThemeTone
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.OursMono
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.work.SyncWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit


/**
 * Settings as a set of labelled panels rather than a Material preference list.
 *
 * Nearly every switch here has a consequence worth a sentence — what sheet sync costs
 * you in privacy, what nearby sync costs in battery — so the prose is the point and the
 * control is the footnote. That inverts the usual list-row shape, where the caption is
 * a grey afterthought under the title.
 */
@Composable
fun SettingsScreen(
    onOpenParserTester: () -> Unit,
    onOpenRules: () -> Unit = {},
    onOpenSheetSetup: () -> Unit = {},
    onScanInvite: () -> Unit = {},
    onOpenDeleteRequests: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-read on every resume, because the only way to change this is to leave for
    // system settings and come back. A value read once at composition would still say
    // "blocked" after the user had just switched it on, which reads as a broken row.
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    // Notification *access* is a separate grant from POST_NOTIFICATIONS: the first lets
    // the app post, this lets it read what other apps post. Reading bank notifications
    // is the whole Notifications ingest source, and it does nothing at all until this
    // is on — there is no permission dialog for it, only a system page the user has to
    // be sent to.
    var listenerEnabled by remember { mutableStateOf(context.notificationAccessGranted()) }
    // Two different targets, in order: taps on the version, then one on the household
    // code. A single repeated tap is something a thumb can do by accident on a screen
    // people scroll; this cannot happen without meaning it.
    var versionTaps by remember { mutableIntStateOf(0) }
    var unlockRefused by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<SettingsGroup?>(null) }
    var search by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                listenerEnabled = context.notificationAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // What is silently broken, in the order it costs you.
    //
    // Recomputed every pass rather than held in state: two of these three live in
    // system settings, where they can be revoked without this app being told.
    val problems = buildList {
        if (!notificationsAllowed) {
            add(
                Problem("Notifications are blocked — nothing reaches you") {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            )
        }
        if (state.ingestSource == IngestSource.NOTIFICATION && !listenerEnabled) {
            add(
                Problem("Notification access is off — no expenses are being read") {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
        if (state.capturePopup && !viewModel.canPopUp()) {
            add(Problem("Popup permission not granted") { context.openOverlaySettings() })
        }
    }

    // Back closes the open page before it closes the screen. Without this, opening
    // Household from the index and pressing back left Settings entirely, which is not
    // where you were.
    BackHandler(enabled = state.settingsIndex && openGroup != null) { openGroup = null }

    // The five groups, written once and drawn by either layout.
    //
    // Local lambdas rather than top-level functions so they keep hold of the launchers,
    // pickers and remembered state each one needs; passing that lot as parameters would
    // be a dozen arguments per group to say nothing extra.
    val householdItems: LazyListScope.() -> Unit = {
        // ─── Household ───────────────────────────────────────────────

        item {
            Panel {
                // One line for the household, not a row each.
                //
                // A member row carrying a name, an email and a pill is a lot of
                // furniture for a fact that never changes and that you already know:
                // who lives here. The names are the answer; the addresses were only
                // ever there because the data had them.
                Text(
                    state.members.joinToString("  ·  ") { it.displayName }
                        .ifBlank { "Just you" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.onSurface,
                )
                MicroLabel(
                    if (state.isHouseholdOwner) "You own this household"
                    else "You joined this household",
                )

                Hairline()
                DisclosureRow(
                    title = if (state.members.size < 2) "Invite your partner"
                    else "Add someone else",
                    caption = "Show the code and QR",
                    expanded = showInvite,
                    onClick = { showInvite = !showInvite },
                )
                if (showInvite) {
                    Note(
                        "Have them install Ours and scan this code, or type it in. It " +
                            "carries the household key — anyone who has it can read " +
                            "your expenses, so share it in person. The same code adds " +
                            "everyone: a partner, a child, anyone in the household."
                    )
                    state.inviteQr?.let { bitmap ->
                        // White plate under the code: a QR on a near-black ground
                        // fails to scan on a good half of phone cameras.
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color.White)
                                .padding(10.dp)
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Invite QR code",
                                modifier = Modifier.size(180.dp),
                            )
                        }
                    }
                    state.inviteSecret?.let { secret ->
                        Text(
                            text = secret,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = OursMono,
                            fontWeight = FontWeight.Bold,
                            color = Ours.onSurface,
                        )
                    }
                    Note("Joining instead? Scan the code on their phone.")
                    GhostButton("Scan their code", onClick = onScanInvite)
                }

                // Only the owner can answer these, and only worth a row when some
                // exist — but inside the panel, because an approval queue between
                // two people is a household matter.
                if (state.isHouseholdOwner && state.pendingDeleteRequests > 0) {
                    Hairline()
                    PanelRow(
                        title = "Delete requests",
                        caption = "They still count until you decide",
                        onClick = onOpenDeleteRequests,
                        trailing = {
                            StatePill("${state.pendingDeleteRequests} waiting", PillTone.Warn)
                        },
                    )
                }
            }
        }
    }
    val syncItems: LazyListScope.() -> Unit = {
        // ─── Sync ────────────────────────────────────────────────────

        item {
            var draft by remember(state.sheetUrl) { mutableStateOf(state.sheetUrl) }
            val status by viewModel.sheetStatus.collectAsStateWithLifecycle()
            val testing by viewModel.sheetTesting.collectAsStateWithLifecycle()
            val progress by viewModel.syncProgress.collectAsStateWithLifecycle()
            val running = progress == "Syncing…"

            val nearbyPermissions = remember { NearbyTransport.requiredPermissions() }
            var nearbyDenied by remember { mutableStateOf(false) }
            val nearbyLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val granted = nearbyPermissions.all { result[it] == true }
                nearbyDenied = !granted
                // Only switch it on once the grant is real. Storing true regardless
                // is what made this look enabled while finding no peers, forever.
                if (granted) viewModel.setNearbyAlways(true)
            }

            Panel {
                // The setup form behind a row, like the invite QR.
                //
                // It is a URL field, three buttons and two warnings — correct on the
                // day you connect a sheet and pure noise on every day after, which is
                // most of them. The row states whether it is on; opening it is for
                // changing that.
                DisclosureRow(
                    title = "Sheet sync",
                    caption = if (state.sheetUrl.isNotBlank()) {
                        "On — both phones read the same sheet"
                    } else {
                        "Off — set up a Google Sheet to sync anywhere"
                    },
                    expanded = showSheet,
                    onClick = { showSheet = !showSheet },
                    trailing = {
                        StatePill(
                            text = if (state.sheetUrl.isNotBlank()) "On" else "Off",
                            tone = if (state.sheetUrl.isNotBlank()) PillTone.Ok
                            else PillTone.Neutral,
                        )
                    },
                )
                if (showSheet) {
                    Note(
                        "Paste the Apps Script URL from your Google Sheet. Use the " +
                            "same URL on your partner's phone and you'll both see " +
                            "every expense, wherever you are."
                    )
                    GhostButton(
                        label = "How do I set this up?",
                        onClick = onOpenSheetSetup,
                    )
                    HairlineField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "https://script.google.com/macros/s/…/exec",
                        enabled = !testing,
                    )
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Connected")) Ours.success
                            else Ours.error,
                        )
                    }
                    GhostButton(
                        label = when {
                            testing -> "Checking…"
                            draft.isBlank() -> "Turn off sheet sync"
                            else -> "Connect"
                        },
                        onClick = { if (!testing) viewModel.saveSheetUrl(draft) },
                    )
                    if (state.sheetUrl.isNotBlank()) {
                        // "Everything" is a promise the cutoff does not keep, so the
                        // button stops making it as soon as there is something retired to
                        // leave out. The count came from observeRetiredCount rather than
                        // from the result, because the moment to learn a re-upload will
                        // skip four months is before pressing it, not after.
                        val retired by viewModel.retiredCount.collectAsStateWithLifecycle()
                        GhostButton(
                            label = if (retired > 0) "Re-upload everything in scope"
                            else "Re-upload everything",
                            onClick = { viewModel.reuploadEverything() },
                        )
                        Note(
                            "For a sheet you recreated or cleared. The phone otherwise " +
                                "believes it already sent everything, and only new " +
                                "expenses would appear."
                        )
                        if (retired > 0 && state.trackingStartAt > 0L) {
                            Note(
                                "${if (retired == 1) "1 expense" else "$retired expenses"} " +
                                    "from before ${formatDay(state.trackingStartAt)} will " +
                                    "not be sent. Retiring a month keeps it off the sheet " +
                                    "and off the other phone, not just off this screen.",
                                tone = Ours.warning,
                            )
                        }
                    }
                    Note(
                        "Anyone with this URL can read and change your expenses — " +
                            "treat it like a password. Amounts, merchants and account " +
                            "tails are stored in the clear; the original bank messages " +
                            "are stripped before they are sent.",
                        tone = Ours.warning,
                    )
                }

                Hairline()
                ToggleRow(
                    title = "Keep syncing when nearby",
                    caption = "Instant sync the moment you're in the same room, over " +
                        "Bluetooth, with no internet and nothing written to the sheet. " +
                        "Costs some battery.",
                    checked = state.nearbyAlways,
                    onCheckedChange = { wanted ->
                        if (!wanted) {
                            viewModel.setNearbyAlways(false)
                            nearbyDenied = false
                        } else if (hasAll(context, nearbyPermissions)) {
                            viewModel.setNearbyAlways(true)
                        } else {
                            nearbyLauncher.launch(nearbyPermissions.toTypedArray())
                        }
                    },
                    padded = false,
                )
                if (nearbyDenied) {
                    Note(
                        "Nearby sync needs Bluetooth permission. Without it the two " +
                            "phones cannot see each other, so this stays off.",
                        tone = Ours.warning,
                    )
                }

                Hairline()
                PanelRow(
                    title = "Sync now",
                    // The result of the last run wins over "synced 4m ago": what it
                    // did is more useful than when it happened.
                    caption = progress ?: state.lastSyncLabel,
                    captionColor = when {
                        running -> Ours.primary
                        progress == "Sync failed" ||
                            progress == "Could not reach anything to sync with" -> Ours.warning
                        else -> Ours.onSurfaceMuted
                    },
                    onClick = { if (!running) SyncWorker.syncNow(context) },
                    trailing = {
                        if (running) {
                            CircularProgressIndicator(
                                color = Ours.primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                        } else {
                            OursIconView(
                                OursIcon.Sync,
                                contentDescription = null,
                                tint = Ours.onSurfaceMuted,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    },
                    chevron = false,
                )
            }
        }
    }
    val entriesItems: LazyListScope.() -> Unit = {
        // ─── What becomes an entry ───────────────────────────────────
        //
        // Message scanning, Where expenses come from and Tracking were three
        // sections with an unrelated one between them, all answering the same
        // question: which payments turn into rows.

        item {
            var granted by remember { mutableStateOf(viewModel.hasSmsPermission()) }
            val scan by viewModel.observeScanProgress()
                .collectAsStateWithLifecycle(initialValue = null)
            val scanning = scan?.let { !it.finished && it.total > 0 } == true

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                granted = result[Manifest.permission.READ_SMS] == true
                // Granting late is exactly the case that used to leave the app
                // permanently empty — scan straight away rather than waiting for the
                // next incoming message.
                if (granted) viewModel.rescanMessages()
            }

            var picking by remember { mutableStateOf(false) }
            val start = state.trackingStartAt

            Panel {
                MicroLabel("Read from")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IngestSource.entries.forEach { source ->
                        OursChip(
                            label = when (source) {
                                IngestSource.SMS -> "SMS"
                                IngestSource.NOTIFICATION -> "Notifications"
                                IngestSource.MANUAL_ONLY -> "Manual"
                            },
                            selected = state.ingestSource == source,
                            onClick = { viewModel.setIngestSource(source) },
                        )
                    }
                }
                Note(
                    when (state.ingestSource) {
                        IngestSource.SMS ->
                            "Reads bank SMS directly. Most reliable, but Google Play " +
                                "restricts this permission — fine for a sideloaded app."
                        IngestSource.NOTIFICATION ->
                            "Reads bank app notifications instead. No restricted " +
                                "permission, but it needs notification access, and " +
                                "only catches alerts you actually receive."
                        IngestSource.MANUAL_ONLY ->
                            "Nothing is read automatically. You add every expense yourself."
                    }
                )

                // The permission each source depends on, stated where the source is
                // chosen. Picking Notifications without the grant is a no-op: the
                // listener is never bound, so nothing is read and the app looks
                // broken in a way that offers no explanation.
                when (state.ingestSource) {
                    IngestSource.SMS -> PermissionRow(
                        title = "Read your bank SMS",
                        granted = granted,
                        onClick = {
                            if (!granted) permissionLauncher.launch(viewModel.smsPermissions)
                        },
                    )
                    IngestSource.NOTIFICATION -> PermissionRow(
                        title = "Notification access",
                        granted = listenerEnabled,
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )
                    IngestSource.MANUAL_ONLY -> Unit
                }

                Hairline()
                PanelRow(
                    title = if (start > 0L) "From ${formatDay(start)}" else "Tracking everything",
                    caption = if (start > 0L) {
                        "Nothing before this date is counted — move it back and those months return"
                    } else {
                        "Every message the scan finds, as far back as six months"
                    },
                    onClick = { picking = true },
                )
                if (start > 0L) {
                    GhostButton(
                        label = "Show everything",
                        onClick = { viewModel.setTrackingStartAt(0L) },
                    )
                }

                Hairline()
                PanelRow(
                    title = when {
                        !granted && state.ingestSource == IngestSource.SMS ->
                            "Turn on SMS access"
                        scanning -> "Scanning…"
                        else -> "Rescan messages"
                    },
                    caption = scan
                        ?.takeIf { scanning }
                        ?.let { "${it.scanned} of ${it.total} · ${it.imported} found" }
                        ?: "If you think something was missed",
                    onClick = {
                        if (scanning) return@PanelRow
                        if (granted) viewModel.rescanMessages()
                        else permissionLauncher.launch(viewModel.smsPermissions)
                    },
                )
                if (scanning) {
                    LinearProgressIndicator(
                        progress = { scan!!.fraction },
                        color = Ours.primary,
                        trackColor = Ours.outlineVariant,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }

                // The tools, below a rule. These are for when something went wrong,
                // not things you set.
                Hairline()
                PanelRow(
                    title = "Auto-assign rules",
                    caption = "Decide what counts as Food, Rent, Groceries — once",
                    onClick = onOpenRules,
                )
                PanelRow(
                    title = "Parser tester",
                    caption = "Paste a bank SMS and see exactly what it parses to",
                    onClick = onOpenParserTester,
                )
            }

            if (picking) {
                TrackingStartPicker(
                    initial = start,
                    onPick = {
                        viewModel.setTrackingStartAt(it)
                        picking = false
                    },
                    onDismiss = { picking = false },
                )
            }
        }
    }
    val paymentItems: LazyListScope.() -> Unit = {
        // ─── When a payment happens ──────────────────────────────────
        //
        // The notification and the popup were five sections apart, which meant you
        // could switch the popup on without ever noticing notifications were
        // blocked. They are one decision made twice, so they are one panel.

        item {
            val permitted = viewModel.canPopUp()

            Panel {
                PanelTitle("Notification")
                Note(
                    "The amount, the payee and three one-tap categories, a second " +
                        "after the bank messages you."
                )
                PermissionRow(
                    title = "Notifications",
                    granted = notificationsAllowed,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                )

                Hairline()

                ToggleRow(
                    title = "Popup over other apps",
                    caption = "Asks for the category, the payee's name and a note the " +
                        "moment a payment lands — over whatever you are doing, so it " +
                        "works when Ours is closed. The notification still arrives " +
                        "either way.",
                    checked = state.capturePopup && permitted,
                    onCheckedChange = { wanted ->
                        viewModel.setCapturePopup(wanted)
                        // Android has no runtime dialog for this one; the only way to
                        // grant it is the system page, so send them straight there
                        // rather than leaving a switch that turns itself back off.
                        if (wanted && !permitted) context.openOverlaySettings()
                    },
                    padded = false,
                )
                // Always drawn, granted or not.
                //
                // It used to appear only while the permission was missing, so once
                // granted there was no way to see it from inside the app, and no way
                // back to the system page to change your mind. A permission the app
                // depends on should be visible in the app that depends on it.
                PermissionRow(
                    title = "Display over other apps",
                    granted = permitted,
                    onClick = { context.openOverlaySettings() },
                )
            }
        }
    }
    val appItems: LazyListScope.() -> Unit = {
        // ─── This app ────────────────────────────────────────────────

        item {
            val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
            val busy by viewModel.updateBusy.collectAsStateWithLifecycle()
            val ready by viewModel.updateFile.collectAsStateWithLifecycle()
            val result by viewModel.update.collectAsStateWithLifecycle()
            val pending = result as? com.manuel.ours.data.update.UpdateChecker.Result.Update

            Panel {
                MicroLabel("Appearance")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        OursChip(
                            label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            selected = state.theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                        )
                    }
                }

                // How hard the palette is, kept separate from which one it is. The
                // printed original runs about 18:1, which is right for a receipt and a
                // lot to read for an hour.
                MicroLabel("Contrast")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeTone.entries.forEach { t ->
                        OursChip(
                            label = if (t == ThemeTone.CRISP) "Crisp" else "Soft",
                            selected = state.tone == t,
                            onClick = { viewModel.setThemeTone(t) },
                        )
                    }
                }
                Note(
                    if (state.tone == ThemeTone.CRISP) {
                        "Printed contrast — near-black on near-white. Sharpest, and the " +
                            "most tiring over a long sitting."
                    } else {
                        "The ground and the ink brought toward each other, and the paper " +
                            "warmed. Easier for a long read, and still well clear of the " +
                            "legibility floor."
                    }
                )

                MicroLabel("Accent")
                // Swatches, not names: the colour is the thing being chosen, and a row
                // of words would make you tap one to find out what it looks like.
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    AccentColor.entries.forEach { a ->
                        AccentSwatch(
                            // The fill, not the text tone: the swatch is a filled circle, and
                            // it has to show the colour the buttons will actually be.
                            colour = a.fill,
                            selected = state.accent == a,
                            label = a.label,
                            onClick = { viewModel.setAccentColor(a) },
                        )
                    }
                }
                Note(
                    "Only the blue-to-violet arc and teal are offered. Green means money " +
                        "arriving here, amber a warning and red a loss — an accent taken " +
                        "from any of those would make the interface say something it does " +
                        "not mean."
                )

                Hairline()
                ToggleRow(
                    title = "Require unlock",
                    caption = "Fingerprint, face or screen lock when you open Ours. " +
                        "Stays unlocked for a minute if you switch away, so it " +
                        "doesn't nag on every glance.",
                    checked = state.appLock,
                    onCheckedChange = viewModel::setAppLock,
                    padded = false,
                )

                // Above the version row rather than below it. This is the only answer in
                // the app to a lost handset, and everything under it is housekeeping.
                Hairline()
                PanelRow(
                    title = "Trash",
                    caption = "Deleted entries, and ${com.manuel.ours.domain.Trash.WINDOW_DAYS} " +
                        "days to change your mind",
                    onClick = onOpenTrash,
                    trailing = {
                        if (trashCount > 0) StatePill("$trashCount", PillTone.Neutral)
                    },
                )

                Hairline()
                PanelRow(
                    title = "Backup & restore",
                    caption = "Manual entries and your category corrections live only on " +
                        "this phone — a rescan cannot bring them back",
                    onClick = onOpenBackup,
                )

                Hairline()
                PanelRow(
                    title = "Ours ${com.manuel.ours.BuildConfig.VERSION_NAME} " +
                        "(${com.manuel.ours.BuildConfig.VERSION_CODE})",
                    caption = updateStatus ?: "Updates come from its own repository",
                    captionColor = when {
                        busy -> Ours.primary
                        updateStatus == null -> Ours.onSurfaceMuted
                        updateStatus!!.startsWith("Could not") ||
                            updateStatus!!.contains("failed", true) ||
                            updateStatus!!.contains("different key") -> Ours.error
                        updateStatus!!.startsWith("Ready") ||
                            updateStatus!!.contains("available") -> Ours.primary
                        else -> Ours.success
                    },
                    // Seven taps here, then the household code, unlocks developer
                    // mode. A single repeated tap is something a thumb can do by
                    // accident on a screen people scroll; this cannot.
                    onClick = {
                        if (state.developerMode) {
                            viewModel.setDeveloperMode(false)
                            versionTaps = 0
                        } else {
                            versionTaps++
                        }
                    },
                    trailing = {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = Ours.primary,
                            )
                        }
                    },
                    chevron = false,
                )
                when {
                    ready != null -> AccentButton(
                        label = "Install now",
                        onClick = {
                            context.installApk(ready!!)
                            viewModel.clearUpdateFile()
                        },
                    )
                    pending != null -> {
                        if (pending.available.notes.isNotBlank()) {
                            Note(pending.available.notes, tone = Ours.onSurface)
                        }
                        AccentButton(
                            label = "Download ${pending.available.versionName}",
                            onClick = { viewModel.downloadUpdate() },
                        )
                    }
                    else -> GhostButton(
                        label = "Check for updates",
                        onClick = { viewModel.checkForUpdate() },
                    )
                }
                Note("A build signed by a different key is refused.")

                Hairline()
                DetailLine(
                    label = "Household code",
                    value = state.inviteSecret ?: "—",
                    onClick = {
                        if (versionTaps >= VERSION_TAPS_TO_UNLOCK) {
                            // Refusing in silence is what made this feel broken: the
                            // sequence completed, nothing happened, and there was no
                            // way to tell a miscount from a refusal.
                            if (state.isHouseholdOwner) viewModel.setDeveloperMode(true)
                            else unlockRefused = true
                        }
                        versionTaps = 0
                    },
                )
                DetailLine("Entries", state.transactionCount.toString())

                Hairline()
                ToggleRow(
                    title = "I own this household",
                    caption = "Other members' deletions come to you for approval, " +
                        "and developer mode is yours to unlock",
                    checked = state.isHouseholdOwner,
                    onCheckedChange = {
                        viewModel.setHouseholdOwner(it)
                        unlockRefused = false
                    },
                    padded = false,
                )

                // Android's own settings count down out loud, and for the same
                // reason: a hidden sequence with no feedback is indistinguishable
                // from one that does not work.
                val remaining = VERSION_TAPS_TO_UNLOCK - versionTaps
                when {
                    state.developerMode -> Unit
                    unlockRefused -> Note(
                        "Only the household owner can unlock this. Turn on \"I own " +
                            "this household\" above if that is you.",
                        tone = Ours.warning,
                    )
                    versionTaps in 1 until VERSION_TAPS_TO_UNLOCK && remaining <= 4 ->
                        MicroLabel("$remaining more taps on the version", color = Ours.primary)
                    versionTaps >= VERSION_TAPS_TO_UNLOCK ->
                        MicroLabel("Now tap the household code", color = Ours.primary)
                    else -> Unit
                }
            }
        }

        // Loose, not panelled. It is the fine print, and a border around it would
        // make it look like another group of settings.
        item {
            Box(Modifier.padding(horizontal = Space.edge)) {
                Note(
                    "The database on this phone is encrypted with a key held in the " +
                        "Android Keystore. Bluetooth sync is encrypted end to end. " +
                        "Sheet sync is not — it writes plain text, so that you can " +
                        "read and fix the sheet yourself. Your original bank messages " +
                        "are stripped before anything is written there."
                )
            }
        }
    }
    val developerItems: LazyListScope.() -> Unit = {
        item {
            Panel {
                Note(
                    "Amounts can be edited on a transaction, and any row you change is " +
                        "stamped as hand-edited — an edited figure no longer matches the " +
                        "bank message it came from. Tap the version again to switch this off.",
                    tone = Ours.warning,
                )
                Hairline()
                PanelRow(
                    title = "Send a test notification",
                    caption = "The expense prompt for a made-up ₹151 payment. Nothing is saved.",
                    onClick = viewModel::sendTestNotification,
                )
                PanelRow(
                    title = "Test the popup",
                    caption = "Press Home straight after tapping. It appears in three " +
                        "seconds, using your newest entry.",
                    onClick = viewModel::sendTestPopup,
                )
            }
        }

        // Both layouts are kept, so this is not a decision waiting to be made — but it
        // is also not a choice worth putting in front of someone who only wants to turn
        // sync on. Behind the developer unlock it stays available without becoming a
        // question the screen asks everybody.
        item {
            Panel {
                MicroLabel("Settings layout")
                Note(
                    "One page is everything in one scroll. Index is five rows that each " +
                        "open their own page — quicker to read, one tap further to change " +
                        "anything."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OursChip(
                        label = "One page",
                        selected = !state.settingsIndex,
                        onClick = { viewModel.setSettingsIndex(false) },
                    )
                    OursChip(
                        label = "Index",
                        selected = state.settingsIndex,
                        onClick = { viewModel.setSettingsIndex(true) },
                    )
                }
            }
        }
    }

    val groupItems: (SettingsGroup) -> (LazyListScope.() -> Unit) = { group ->
        when (group) {
            SettingsGroup.HOUSEHOLD -> householdItems
            SettingsGroup.SYNC -> syncItems
            SettingsGroup.ENTRIES -> entriesItems
            SettingsGroup.PAYMENT -> paymentItems
            SettingsGroup.APP -> appItems
            SettingsGroup.DEVELOPER -> developerItems
        }
    }

    // What each index row says about itself, so the index is a status report and not
    // only a menu — the thing worth keeping from that design either way.
    val summaryOf: (SettingsGroup) -> String = { group ->
        when (group) {
            SettingsGroup.HOUSEHOLD -> buildString {
                append(state.members.joinToString(", ") { it.displayName }.ifBlank { "Just you" })
                if (state.isHouseholdOwner && state.pendingDeleteRequests > 0) {
                    append(" · ${state.pendingDeleteRequests} delete requests")
                }
            }
            SettingsGroup.SYNC -> buildString {
                append(if (state.sheetUrl.isNotBlank()) "Sheet on" else "Sheet off")
                append(if (state.nearbyAlways) " · Bluetooth on" else " · Bluetooth off")
                append(" · ${state.lastSyncLabel.lowercase()}")
            }
            SettingsGroup.ENTRIES -> buildString {
                append(
                    when (state.ingestSource) {
                        IngestSource.SMS -> "From SMS"
                        IngestSource.NOTIFICATION -> "From notifications"
                        IngestSource.MANUAL_ONLY -> "By hand only"
                    }
                )
                append(
                    if (state.trackingStartAt > 0L) ", since ${formatDay(state.trackingStartAt)}"
                    else ", everything"
                )
            }
            SettingsGroup.PAYMENT -> buildString {
                append(if (notificationsAllowed) "Notification on" else "Notification blocked")
                append(
                    when {
                        !state.capturePopup -> " · popup off"
                        !viewModel.canPopUp() -> " · popup needs permission"
                        else -> " · popup on"
                    }
                )
            }
            SettingsGroup.APP -> "Ours ${com.manuel.ours.BuildConfig.VERSION_NAME} " +
                "(${com.manuel.ours.BuildConfig.VERSION_CODE})"
            SettingsGroup.DEVELOPER -> "Test rows and amount editing"
        }
    }

    Scaffold(modifier = Modifier.imePadding(), containerColor = Ours.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val open = openGroup
            when {
                // ─── One page ────────────────────────────────────────────
                !state.settingsIndex -> {

                    item {
                        OursTopBar(title = "Settings") {
                            StatePill(
                                text = if (state.lastSyncLabel == "Never synced") "Off" else "Synced",
                                tone = if (state.lastSyncLabel == "Never synced") PillTone.Neutral
                                else PillTone.Ok,
                                icon = OursIcon.Done.takeIf { state.lastSyncLabel != "Never synced" },
                            )
                        }
                    }
                    // A search field, because a screen this size is not navigable by scrolling.
                    item { SettingsSearch(query = search, onQueryChange = { search = it }) }
                    // ─── Anything that is silently broken ────────────────────────
                    //
                    // Every failure this app has actually had was a permission that was off
                    // while everything else looked fine: POST_NOTIFICATIONS never requested,
                    // notification access never granted, and the overlay permission, which
                    // Android can revoke at any time without telling anyone. A screen that only
                    // reveals that once you scroll to the right section will not catch it.
                    //
                    // Drawn only when something is wrong, and absent entirely when nothing is —
                    // a banner that is always there stops being read.
                    if (problems.isNotEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = Space.edge),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                problems.forEach { problem ->
                                    NeedsYouRow(text = problem.text, onClick = problem.fix)
                                }
                            }
                        }
                    }
                    item {
                        Section(
                            "Household",
                            trailing = if (state.members.size == 1) "1 person"
                            else "${state.members.size} people",
                        )
                    }
                    householdItems()

                    item { Section("Sync", trailing = state.lastSyncLabel) }
                    syncItems()

                    item {
                        Section(
                            "What becomes an entry",
                            trailing = when (state.ingestSource) {
                                IngestSource.SMS -> "From SMS"
                                IngestSource.NOTIFICATION -> "From notifications"
                                IngestSource.MANUAL_ONLY -> "By hand"
                            },
                        )
                    }
                    entriesItems()

                    item { Section("When a payment happens") }
                    paymentItems()

                    item {
                        Section(
                            "This app",
                            trailing = "Ours ${com.manuel.ours.BuildConfig.VERSION_NAME}",
                        )
                    }
                    appItems()

                    if (state.developerMode) {
                        item { Section("Developer") }
                        developerItems()
                    }
                }

                // ─── Index: one open page ────────────────────────────────
                open != null -> {
                    item {
                        // The last hand-rolled header in the app. Back closes the open page
                        // rather than the screen — without that, opening Household from the
                        // index and pressing back left Settings entirely, which is not where
                        // you were.
                        OursTopBar(
                            title = open.title,
                            onBack = { openGroup = null },
                        )
                    }
                    groupItems(open)()
                }

                // ─── Index: the list of pages ────────────────────────────
                else -> {

                    item {
                        OursTopBar(title = "Settings") {
                            StatePill(
                                text = if (state.lastSyncLabel == "Never synced") "Off" else "Synced",
                                tone = if (state.lastSyncLabel == "Never synced") PillTone.Neutral
                                else PillTone.Ok,
                                icon = OursIcon.Done.takeIf { state.lastSyncLabel != "Never synced" },
                            )
                        }
                    }
                    // A search field, because a screen this size is not navigable by scrolling.
                    item { SettingsSearch(query = search, onQueryChange = { search = it }) }
                    // ─── Anything that is silently broken ────────────────────────
                    //
                    // Every failure this app has actually had was a permission that was off
                    // while everything else looked fine: POST_NOTIFICATIONS never requested,
                    // notification access never granted, and the overlay permission, which
                    // Android can revoke at any time without telling anyone. A screen that only
                    // reveals that once you scroll to the right section will not catch it.
                    //
                    // Drawn only when something is wrong, and absent entirely when nothing is —
                    // a banner that is always there stops being read.
                    if (problems.isNotEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = Space.edge),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                problems.forEach { problem ->
                                    NeedsYouRow(text = problem.text, onClick = problem.fix)
                                }
                            }
                        }
                    }
                    item {
                        val groups = SettingsGroup.entries
                            .filter { it != SettingsGroup.DEVELOPER || state.developerMode }
                            .filter { group ->
                                search.isBlank() ||
                                    group.title.contains(search, ignoreCase = true) ||
                                    group.keywords.any { it.contains(search, ignoreCase = true) } ||
                                    summaryOf(group).contains(search, ignoreCase = true)
                            }
                        if (groups.isEmpty()) {
                            EmptyState(
                                title = "Nothing matches \"$search\"",
                                body = "Try a shorter word — the search covers each page's name " +
                                    "and what it currently says about itself.",
                                icon = OursIcon.NoResults,
                            )
                        } else {
                            Panel {
                                groups.forEachIndexed { index, group ->
                                    if (index > 0) Hairline()
                                    PanelRow(
                                        title = group.title,
                                        caption = summaryOf(group),
                                        onClick = { openGroup = group },
                                    )
                                }
                            }
                        }
                    }
                    // Trash and Backup, lifted out of "This app".
                    //
                    // They are the answers to "I deleted something" and "the phone is gone", not
                    // housekeeping beside a version number — and they were three taps deep.
                    if (search.isBlank()) {
                        item {
                            Panel {
                                PanelRow(
                                    title = "Trash",
                                    caption = "Deleted entries, and " +
                                        "${com.manuel.ours.domain.Trash.WINDOW_DAYS} days to " +
                                        "change your mind",
                                    onClick = onOpenTrash,
                                    trailing = {
                                        if (trashCount > 0) StatePill("$trashCount", PillTone.Neutral)
                                    },
                                )
                                Hairline()
                                PanelRow(
                                    title = "Backup & restore",
                                    caption = "Manual entries and your category corrections live " +
                                        "only on this phone — a rescan cannot bring them back",
                                    onClick = onOpenBackup,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The five pages of Settings, plus the one that only exists when unlocked. */
private enum class SettingsGroup(val title: String, val keywords: List<String> = emptyList()) {
    HOUSEHOLD("Household", listOf("invite", "partner", "member", "qr", "code", "delete requests")),
    SYNC("Sync", listOf("sheet", "google", "bluetooth", "nearby", "upload")),
    ENTRIES("What becomes an entry", listOf("sms", "notification", "rescan", "rules", "parser", "tracking", "date")),
    PAYMENT("When a payment happens", listOf("notification", "popup", "overlay", "alert")),
    APP("This app", listOf("theme", "dark", "light", "contrast", "accent", "colour", "color", "lock", "unlock", "version", "update")),
    DEVELOPER("Developer", listOf("test", "amount", "edit")),
}

/**
 * The one field that makes a screen this size usable.
 *
 * It matches a page's name, the keywords above, and what the page currently *says about itself* —
 * so "bluetooth off" finds Sync, and so does "synced 4m ago".
 */
@Composable
private fun SettingsSearch(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge)
            .clip(RoundedCornerShape(percent = 50))
            .background(Ours.surfaceContainer)
            .padding(horizontal = Space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        OursIconView(
            OursIcon.Search,
            contentDescription = null,
            tint = Ours.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (query.isEmpty()) {
                Text(
                    "Search settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ours.onSurfaceMuted,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.bodyMedium)
                    .copy(color = Ours.onSurface),
                cursorBrush = SolidColor(Ours.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            OursIconButton(
                icon = OursIcon.Dismiss,
                contentDescription = "Clear the search",
                onClick = { onQueryChange("") },
                tint = Ours.onSurfaceVariant,
                glyph = 16.dp,
                size = Space.targetTight,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pieces
// ─────────────────────────────────────────────────────────────────────────────

/** Something that is off and stops part of the app working, with the way to fix it. */
private data class Problem(val text: String, val fix: () -> Unit)

/** Opens the system page for "Display over other apps". There is no other route. */
private fun Context.openOverlaySettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * One thing that needs the reader, at the top of the screen.
 *
 * Amber rather than red: nothing is lost, something is merely not running. Red is for
 * a figure that is wrong, and none of these make the ledger wrong.
 */
@Composable
private fun NeedsYouRow(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ours.warning.copy(alpha = 0.13f))
            .border(1.dp, Ours.warning.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        MicroLabel("Needs you", color = Ours.warning)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Ours.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = MaterialTheme.typography.bodyLarge, color = Ours.warning)
    }
}

/**
 * A permission, stated where the switch that needs it lives.
 *
 * Drawn whether or not it is granted. Shown only while missing, it disappeared the
 * moment it started working — so there was no way to check it later, and no way back to
 * the system page to change your mind. Both of these permissions are revocable outside
 * this app, which makes "currently granted" a fact worth being able to look up.
 */
@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = Ours.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        StatePill(
            text = if (granted) "Granted" else "Not granted",
            tone = if (granted) PillTone.Ok else PillTone.Warn,
        )
        Text("›", style = MaterialTheme.typography.bodyLarge, color = Ours.onSurfaceMuted)
    }
}

/** A row that opens something below itself, with a chevron that turns. */
@Composable
private fun DisclosureRow(
    title: String,
    caption: String,
    expanded: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.onSurface,
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceVariant,
            )
        }
        trailing?.invoke()
        Text(
            if (expanded) "⌃" else "›",
            style = MaterialTheme.typography.bodyLarge,
            color = Ours.onSurfaceMuted,
        )
    }
}

@Composable
private fun Section(label: String, trailing: String? = null) {
    TapeHeader(
        label,
        trailing = trailing,
        modifier = Modifier.padding(horizontal = Space.edge, vertical = 6.dp),
    )
}

/**
 * A row inside a [Panel]: title, one line of caption, optional trailing thing.
 *
 * The panels used to be built from whatever each setting happened to need — a
 * PanelTitle here, a GhostButton there, a bare Row somewhere else — so two settings that
 * read the same on paper looked nothing alike on screen. One row shape means a panel can
 * be scanned down its left edge.
 */
@Composable
private fun PanelRow(
    title: String,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
    captionColor: Color = Ours.onSurfaceVariant,
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.onSurface,
            )
            // Sentence case and wrapping, not a MicroLabel.
            //
            // MicroLabel uppercases and clips to one line, which is right for a caption
            // over a figure and wrong for a sentence: half of these read as SHOUTING AND
            // THEN STOPPING MID-…
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = captionColor,
                )
            }
        }
        trailing?.invoke()
        if (chevron && onClick != null) {
            Text("›", style = MaterialTheme.typography.bodyLarge, color = Ours.onSurfaceMuted)
        }
    }
}

/** One accent option: the colour itself, ringed when chosen. */
@Composable
private fun AccentSwatch(
    colour: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            // The ring sits outside the fill rather than over it, so the colour being
            // judged is never the colour with a line through it.
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Ours.onSurface else Ours.outlineVariant,
                shape = CircleShape,
            )
            .padding(if (selected) 4.dp else 3.dp)
            .clip(CircleShape)
            .background(colour)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            OursIconView(
                OursIcon.Done,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** A bordered group. The border is what separates topics; there are no cards here. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, Ours.outlineVariant, RoundedCornerShape(13.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = Ours.onSurface,
    )
}

@Composable
private fun Note(text: String, tone: Color = Ours.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = tone)
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
}

@Composable
private fun SettingRow(
    title: String,
    caption: String?,
    onClick: () -> Unit,
    /** False inside a [Panel], which has already paid the edge inset. */
    padded: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (padded) Space.edge else 0.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.onSurface,
            )
            caption?.let { MicroLabel(it) }
        }
        OursIconView(
            OursIcon.More,
            contentDescription = null,
            tint = Ours.onSurfaceMuted,
            modifier = Modifier.size(11.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** Panels already inset their contents; a standalone row has to inset itself. */
    padded: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(horizontal = Space.edge) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.onSurface,
            )
            Note(caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ours.primaryFixed,
                checkedBorderColor = Ours.primaryFixed,
                uncheckedThumbColor = Ours.onSurfaceMuted,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = Ours.outlineVariant,
            ),
        )
    }
}

/** A single-line field with a hairline ground, matching the search field on Activity. */
@Composable
private fun HairlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Ours.surfaceContainerHigh)
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceMuted,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle = LocalTextStyle.current
                .merge(MaterialTheme.typography.bodySmall)
                .copy(color = Ours.onSurface),
            cursorBrush = SolidColor(Ours.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Date picker for the tracking start.
 *
 * Snapped to the *start* of the chosen day, and future dates are refused — a start date
 * of tomorrow would silently hide today's spending with no way to tell that was why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingStartPicker(
    initial: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = OursZone.today()
    // The picker speaks UTC in both directions, so the initial value has to be UTC
    // midnight of the intended *local* day. Handing it local midnight put IST users a
    // day behind — it opened on the 2nd when today was the 3rd.
    val initialDay = if (initial > 0L) OursZone.dateOf(initial) else today
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDay.atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableYear(year: Int) = year <= today.year
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return !day.isAfter(today)
            }
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = Ours.surface),
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis ?: return@TextButton
                    // The picker reports UTC midnight; convert to local midnight, or a
                    // user east of UTC loses the first hours of their chosen day.
                    val day = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                    // Local midnight in the household's zone, which is the same zone the
                    // months are bucketed in. Using the device zone here is what made an IST
                    // user's cutoff land on the wrong day.
                    onPick(OursZone.startOfDay(day))
                }
            ) { Text("Start here", color = Ours.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
        },
    ) {
        DatePicker(state = state, title = null)
    }
}

/** Internal, like [relativeSyncLabel]: the view model dates the cutoff the same way. */
internal fun formatDay(epochMillis: Long): String =
    OursZone.format(epochMillis, OursZone.date)

private fun hasAll(context: android.content.Context, permissions: List<String>): Boolean =
    permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

internal fun relativeSyncLabel(epochMillis: Long): String {
    if (epochMillis == 0L) return "Never synced"
    val delta = System.currentTimeMillis() - epochMillis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> "Synced ${minutes}m ago"
        hours < 24 -> "Synced ${hours}h ago"
        else -> "Synced ${TimeUnit.MILLISECONDS.toDays(delta)}d ago"
    }
}

/**
 * Whether the user has given Ours access to *read* other apps' notifications.
 *
 * There is no runtime-permission dialog for this one — the grant lives on a system
 * page, and an app can only check the list and send the user there.
 */
private fun Context.notificationAccessGranted(): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

private const val VERSION_TAPS_TO_UNLOCK = 7

/** A label and a value on one line, optionally tappable. */
@Composable
private fun DetailLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicroLabel(label)
        Text(
            value,
            style = ValueTextStyle,
            color = Ours.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Hands the verified file to the system installer.
 *
 * The app never installs anything itself — it opens Android's own installer, which
 * shows the user what is about to happen and asks. A silent self-update would be a
 * strictly worse thing to build even where it is possible.
 */
private fun Context.installApk(apk: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        this, "$packageName.fileprovider", apk,
    )
    startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
