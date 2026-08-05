package com.manuel.ours.ui.screens.settings

import android.Manifest
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import com.manuel.ours.data.prefs.IngestSource
import com.manuel.ours.data.prefs.ThemeMode
import com.manuel.ours.data.sync.NearbyTransport
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.PillTone
import com.manuel.ours.ui.components.StatePill
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.OursMono
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.WordmarkStyle
import com.manuel.ours.work.SyncWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private val EDGE = 15.dp

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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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

    Scaffold(containerColor = Ours.ink) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // 96dp is Home's allowance for the floating add button, and Activity's
            // for the undo snackbar. Nothing floats over this list, so the same figure
            // was simply a screen-height of blank below the last panel.
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SETTINGS", style = WordmarkStyle, color = Ours.text)
                    StatePill(
                        text = if (state.lastSyncLabel == "Never synced") "Off" else "Synced",
                        tone = if (state.lastSyncLabel == "Never synced") PillTone.Neutral
                        else PillTone.Ok,
                        icon = BiIcon.Done.takeIf { state.lastSyncLabel != "Never synced" },
                    )
                }
            }

            // ─── Anything that is silently broken ────────────────────────
            //
            // Every failure this app has actually had was a permission that was off
            // while everything else looked fine: POST_NOTIFICATIONS never requested,
            // notification access never granted, and now the overlay permission, which
            // Android can revoke at any time without telling anyone. A screen that only
            // reveals that once you scroll to the right section will not catch it.
            //
            // Drawn only when something is wrong, and absent entirely when nothing is —
            // a banner that is always there stops being read.
            if (problems.isNotEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = EDGE),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        problems.forEach { problem ->
                            NeedsYouRow(text = problem.text, onClick = problem.fix)
                        }
                    }
                }
            }

            // ─── Household ───────────────────────────────────────────────

            item { Section("Household") }

            item {
                Panel {
                    state.members.forEach { member ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    member.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ours.text,
                                )
                                MicroLabel(member.email)
                            }
                            if (member.isSelf) StatePill("You", PillTone.Ok)
                        }
                    }

                    // Always available. This used to hide once two people existed,
                    // which made a third member impossible to add — there is no other
                    // route to the code, so the household was silently capped at two.
                    // Behind a tap.
                    //
                    // The QR is a 180dp white square needed exactly once per person, and
                    // drawn always it made Household the tallest section in the app —
                    // every visit paid for a thing you use on one day.
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
                                color = Ours.text,
                            )
                        }
                        Hairline()
                        Note("Joining instead? Scan the code on their phone.")
                        GhostButton("Scan their code", onClick = onScanInvite)
                    }
                }
            }

            // Only the owner can answer these, and only worth a row when some exist.
            if (state.isHouseholdOwner && state.pendingDeleteRequests > 0) {
                item {
                    SettingRow(
                        title = "Delete requests",
                        caption = "${state.pendingDeleteRequests} waiting on you · they still count until you decide",
                        onClick = onOpenDeleteRequests,
                    )
                }
            }

            // ─── Sync ───────────────────────────────────────────────────

            item { Section("Sync") }

            item {
                val progress by viewModel.syncProgress.collectAsStateWithLifecycle()
                val running = progress == "Syncing…"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !running) { SyncWorker.syncNow(context) }
                        .padding(horizontal = EDGE, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Sync now",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Ours.text,
                        )
                        // The result of the last run wins over "synced 4m ago": what it
                        // did is more useful than when it happened.
                        MicroLabel(
                            text = progress ?: state.lastSyncLabel,
                            color = when {
                                running -> Ours.accent
                                progress == "Sync failed" ||
                                    progress == "Could not reach anything to sync with" -> Ours.warning
                                else -> Ours.textLabel
                            },
                        )
                    }
                    if (running) {
                        CircularProgressIndicator(
                            color = Ours.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        BiIconView(
                            BiIcon.Sync,
                            contentDescription = null,
                            tint = Ours.textLabel,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }

            item {
                var draft by remember(state.sheetUrl) { mutableStateOf(state.sheetUrl) }
                val status by viewModel.sheetStatus.collectAsStateWithLifecycle()
                val testing by viewModel.sheetTesting.collectAsStateWithLifecycle()

                Panel {
                    PanelTitle(
                        if (state.sheetUrl.isNotBlank()) "Sheet sync is on" else "Sheet sync"
                    )
                    Note(
                        "Paste the Apps Script URL from your Google Sheet. Use the same " +
                            "URL on your partner's phone and you'll both see every " +
                            "expense, wherever you are."
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
                            color = if (it.startsWith("Connected")) Ours.positive
                            else Ours.negative,
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
                        GhostButton(
                            label = "Re-upload everything",
                            onClick = { viewModel.reuploadEverything() },
                        )
                        Note(
                            "For a sheet you recreated or cleared. The phone otherwise " +
                                "believes it already sent everything, and only new " +
                                "expenses would appear."
                        )
                    }
                    Note(
                        "Anyone with this URL can read and change your expenses — treat it " +
                            "like a password. Amounts, merchants and account tails are " +
                            "stored in the clear; the original bank messages are stripped " +
                            "before they are sent.",
                        tone = Ours.warning,
                    )
                }
            }

            item {
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

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    )
                    if (nearbyDenied) {
                        Box(Modifier.padding(horizontal = EDGE)) {
                            Note(
                                "Nearby sync needs Bluetooth permission. Without it the " +
                                    "two phones cannot see each other, so this stays off.",
                                tone = Ours.warning,
                            )
                        }
                    }
                }
            }

            // ─── What becomes an entry ──────────────────────────────────

            //
            // Message scanning, Where expenses come from and Tracking were three
            // sections with an unrelated one between them, all answering the same
            // question: which payments turn into rows.

            item { Section("What becomes an entry") }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
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

                    // Picking Notifications without this grant is a no-op: the listener
                    // is never bound, so nothing is read and the app looks broken in a
                    // way that offers no explanation. There is no runtime dialog for
                    // notification access — the only route is this system page.
                    if (state.ingestSource == IngestSource.NOTIFICATION && !listenerEnabled) {
                        Note(
                            "Notification access is off, so nothing is being read. " +
                                "Ours needs permission to see other apps' notifications " +
                                "before this source does anything.",
                            tone = Ours.warning,
                        )
                        GhostButton(
                            label = "Grant notification access",
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                        )
                    }
                }
            }

            item {
                var granted by remember { mutableStateOf(viewModel.hasSmsPermission()) }
                val progress by viewModel.observeScanProgress()
                    .collectAsStateWithLifecycle(initialValue = null)

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    granted = result[Manifest.permission.READ_SMS] == true
                    // Granting late is exactly the case that used to leave the app
                    // permanently empty — scan straight away rather than waiting for the
                    // next incoming message.
                    if (granted) viewModel.rescanMessages()
                }

                val running = progress?.let { !it.finished && it.total > 0 } == true

                Panel {
                    PanelTitle(if (granted) "Reading your bank SMS" else "SMS access is off")
                    Note(
                        if (granted) {
                            "New bank messages are picked up automatically. Rescan if you " +
                                "think something was missed."
                        } else {
                            "Nothing is being tracked automatically. Grant access and your " +
                                "last 6 months will be imported — message text never " +
                                "leaves this phone."
                        }
                    )
                    if (running) {
                        val p = progress!!
                        LinearProgressIndicator(
                            progress = { p.fraction },
                            color = Ours.accent,
                            trackColor = Ours.hairline,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                        MicroLabel(
                            "${p.scanned} of ${p.total} messages · ${p.imported} found"
                        )
                    }
                    GhostButton(
                        label = when {
                            !granted -> "Turn on SMS access"
                            running -> "Scanning…"
                            else -> "Rescan messages"
                        },
                        onClick = {
                            if (running) return@GhostButton
                            if (granted) viewModel.rescanMessages()
                            else permissionLauncher.launch(viewModel.smsPermissions)
                        },
                    )
                }
            }

            item {
                var picking by remember { mutableStateOf(false) }
                val start = state.trackingStartAt

                Panel {
                    PanelTitle(
                        if (start > 0L) "Tracking from ${formatDay(start)}" else "Tracking everything"
                    )
                    Note(
                        if (start > 0L) {
                            "Nothing before this date is counted or re-imported. The older " +
                                "messages are still on this phone and still in the database — " +
                                "move the date back and they return."
                        } else {
                            "Every message the scan finds is counted, as far back as six " +
                                "months. Set a start date to retire the months before it."
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.weight(1f)) {
                            GhostButton(
                                label = if (start > 0L) "Change date" else "Set a start date",
                                onClick = { picking = true },
                            )
                        }
                        if (start > 0L) {
                            Box(Modifier.weight(1f)) {
                                GhostButton(
                                    label = "Show everything",
                                    onClick = { viewModel.setTrackingStartAt(0L) },
                                )
                            }
                        }
                    }
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

            // The tools, below the settings. These are for when something went
            // wrong, not things you set.

            item {
                SettingRow(
                    title = "Auto-assign rules",
                    caption = "Decide what counts as Food, Rent, Groceries — once",
                    onClick = onOpenRules,
                )
            }

            item {
                SettingRow(
                    title = "Parser tester",
                    caption = "Paste a bank SMS and see exactly what it parses to",
                    onClick = onOpenParserTester,
                )
            }

            // ─── When a payment happens ──────────────────────────────────
            //
            // The notification and the popup were five sections apart, which meant you
            // could switch the popup on without ever noticing notifications were
            // blocked. They are one decision made twice, so they are one panel.
            item { Section("When a payment happens") }

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

            // ─── This app ────────────────────────────────────────────────

            item { Section("This app") }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        OursChip(
                            label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            selected = state.theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                        )
                    }
                }
            }

            item {
                ToggleRow(
                    title = "Require unlock",
                    caption = "Fingerprint, face or screen lock when you open Ours. Stays " +
                        "unlocked for a minute if you switch away, so it doesn't nag on " +
                        "every glance.",
                    checked = state.appLock,
                    onCheckedChange = viewModel::setAppLock,
                )
            }

            item {
                val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
                val busy by viewModel.updateBusy.collectAsStateWithLifecycle()
                val ready by viewModel.updateFile.collectAsStateWithLifecycle()
                val result by viewModel.update.collectAsStateWithLifecycle()

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Note(
                        "Ours is not on Play, so it updates itself from its own " +
                            "repository. Nothing downloads until you ask, and a build " +
                            "signed by a different key is refused."
                    )

                    val pending = result as? com.manuel.ours.data.update.UpdateChecker.Result.Update
                    if (pending != null && pending.available.notes.isNotBlank()) {
                        Note(pending.available.notes, tone = Ours.text)
                    }

                    when {
                        ready != null -> AccentButton(
                            label = "Install now",
                            onClick = {
                                context.installApk(ready!!)
                                viewModel.clearUpdateFile()
                            },
                        )
                        pending != null -> AccentButton(
                            label = "Download ${pending.available.versionName}",
                            onClick = { viewModel.downloadUpdate() },
                        )
                        else -> GhostButton(
                            label = "Check for updates",
                            onClick = { viewModel.checkForUpdate() },
                        )
                    }

                    // Working, good news and bad news read differently at a glance.
                    // A status line that is the same grey whether it found an update,
                    // found nothing or failed makes the reader parse the sentence to
                    // learn something the colour could have told them.
                    updateStatus?.let { line ->
                        val tone = when {
                            busy -> Ours.accent
                            line.startsWith("Could not") || line.contains("failed", true) ||
                                line.contains("different key") -> Ours.negative
                            line.startsWith("Ready") || line.contains("available") -> Ours.accent
                            else -> Ours.positive
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = Ours.accent,
                                )
                            }
                            MicroLabel(line, color = tone)
                        }
                    }
                }
            }

            item {
                Panel {
                    val version = "Ours ${com.manuel.ours.BuildConfig.VERSION_NAME} " +
                        "(${com.manuel.ours.BuildConfig.VERSION_CODE})"
                    DetailLine(
                        label = "Version",
                        value = version,
                        onClick = {
                            if (state.developerMode) {
                                viewModel.setDeveloperMode(false)
                                versionTaps = 0
                            } else {
                                versionTaps++
                            }
                        },
                    )
                    DetailLine(
                        label = "Household code",
                        value = state.inviteSecret ?: "—",
                        onClick = {
                            if (versionTaps >= VERSION_TAPS_TO_UNLOCK) {
                                // Refusing in silence is what made this feel broken:
                                // the sequence completed, nothing happened, and there
                                // was no way to tell a miscount from a refusal.
                                if (state.isHouseholdOwner) viewModel.setDeveloperMode(true)
                                else unlockRefused = true
                            }
                            versionTaps = 0
                        },
                    )
                    DetailLine("Expenses", state.transactionCount.toString())

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
                    Note(
                        "Nothing recorded who created this household — the app was not " +
                            "keeping that when yours was made, and cannot work it out " +
                            "afterwards, so it asks. A speed bump against an accidental " +
                            "delete among people who trust each other, not a lock."
                    )

                    // Android's own settings count down out loud, and for the same
                    // reason: a hidden sequence with no feedback is indistinguishable
                    // from one that does not work.
                    val remaining = VERSION_TAPS_TO_UNLOCK - versionTaps
                    when {
                        state.developerMode -> Unit
                        unlockRefused -> Note(
                            "Only the household owner can unlock this. Turn on \"I own " +
                                "this household\" below if that is you.",
                            tone = Ours.warning,
                        )
                        versionTaps in 1 until VERSION_TAPS_TO_UNLOCK && remaining <= 4 ->
                            MicroLabel("$remaining more taps on the version", color = Ours.accent)
                        versionTaps >= VERSION_TAPS_TO_UNLOCK ->
                            MicroLabel("Now tap the household code", color = Ours.accent)
                        else -> Unit
                    }

                }
            }

            item {
                Panel {
                    Note(
                        "The database on this phone is encrypted with a key held in the " +
                            "Android Keystore. Bluetooth sync is encrypted end to end. " +
                            "Sheet sync is not — it writes plain text, so that you can " +
                            "read and fix the sheet yourself. Your original bank messages " +
                            "are stripped before anything is written there."
                    )
                }
            }

            // ─── Developer ───────────────────────────────────────────────
            //
            // Its own panel, below the encryption note, rather than rows in the middle
            // of a panel about which version you are running. For anyone who has not
            // unlocked it the screen has already ended here.
            if (state.developerMode) {
                item { Section("Developer") }

                item {
                    Panel {
                        Note(
                            "Amounts can be edited on a transaction, and any row you " +
                                "change is stamped as hand-edited — an edited figure no " +
                                "longer matches the bank message it came from. Tap the " +
                                "version again to switch this off.",
                            tone = Ours.warning,
                        )
                        Hairline()
                        SettingRow(
                            title = "Send a test notification",
                            caption = "Shows the expense prompt for a made-up ₹151 " +
                                "payment. Nothing is saved.",
                            onClick = viewModel::sendTestNotification,
                            padded = false,
                        )
                        SettingRow(
                            title = "Test the popup",
                            caption = "Press Home straight after tapping this. The popup " +
                                "appears in three seconds, using your newest entry — it " +
                                "stays away while Ours is in front, which is the part " +
                                "worth testing.",
                            onClick = viewModel::sendTestPopup,
                            padded = false,
                        )
                    }
                }
            }
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
            color = Ours.textSecondary,
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
            color = Ours.textSecondary,
            modifier = Modifier.weight(1f),
        )
        StatePill(
            text = if (granted) "Granted" else "Not granted",
            tone = if (granted) PillTone.Ok else PillTone.Warn,
        )
        Text("›", style = MaterialTheme.typography.bodyLarge, color = Ours.textLabel)
    }
}

/** A row that opens something below itself, with a chevron that turns. */
@Composable
private fun DisclosureRow(
    title: String,
    caption: String,
    expanded: Boolean,
    onClick: () -> Unit,
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
                color = Ours.text,
            )
            MicroLabel(caption)
        }
        Text(
            if (expanded) "⌃" else "›",
            style = MaterialTheme.typography.bodyLarge,
            color = Ours.textLabel,
        )
    }
}

@Composable
private fun Section(label: String) {
    TapeHeader(label, modifier = Modifier.padding(horizontal = EDGE, vertical = 6.dp))
}

/** A bordered group. The border is what separates topics; there are no cards here. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = EDGE)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, Ours.hairline, RoundedCornerShape(13.dp))
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
        color = Ours.text,
    )
}

@Composable
private fun Note(text: String, tone: Color = Ours.textSecondary) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = tone)
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
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
            .padding(horizontal = if (padded) EDGE else 0.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.text,
            )
            caption?.let { MicroLabel(it) }
        }
        BiIconView(
            BiIcon.NextMonth,
            contentDescription = null,
            tint = Ours.textLabel,
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
            .then(if (padded) Modifier.padding(horizontal = EDGE) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.text,
            )
            Note(caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ours.accent,
                checkedBorderColor = Ours.accent,
                uncheckedThumbColor = Ours.textLabel,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = Ours.hairline,
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
            .background(Ours.surfaceHigh)
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = Ours.textLabel,
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
                .copy(color = Ours.text),
            cursorBrush = SolidColor(Ours.accent),
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
    val today = LocalDate.now(ZoneId.systemDefault())
    // The picker speaks UTC in both directions, so the initial value has to be UTC
    // midnight of the intended *local* day. Handing it local midnight put IST users a
    // day behind — it opened on the 2nd when today was the 3rd.
    val initialDay = if (initial > 0L) {
        Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault()).toLocalDate()
    } else today
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
        colors = DatePickerDefaults.colors(containerColor = Ours.ink),
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis ?: return@TextButton
                    // The picker reports UTC midnight; convert to local midnight, or a
                    // user east of UTC loses the first hours of their chosen day.
                    val day = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                }
            ) { Text("Start here", color = Ours.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.textSecondary) }
        },
    ) {
        DatePicker(state = state, title = null)
    }
}

private fun formatDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))

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
            color = Ours.text,
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
