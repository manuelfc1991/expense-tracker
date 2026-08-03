package com.manuel.ours.ui.screens.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.Ruler
import com.manuel.ours.ui.theme.Ours

private data class Page(
    @DrawableRes val icon: Int,
    val title: String,
    val body: String,
)

private val pages = listOf(
    Page(
        icon = BiIcon.Message,
        title = "Your expenses, without the data entry",
        body = "Ours reads the SMS your bank already sends you and turns each one " +
            "into a categorised expense. You don't type anything.",
    ),
    Page(
        icon = BiIcon.Household,
        title = "One budget, two phones",
        body = "You pay from your phone, your partner pays from theirs, and both of you " +
            "see one household total. Each phone reads only its own messages.",
    ),
    Page(
        icon = BiIcon.Privacy,
        title = "Nobody else can read it",
        body = "No account and no server of ours. Your messages are read on this " +
            "phone, and you choose how the two phones share: a Google Sheet you own, " +
            "a shared folder, or Bluetooth when you're together.",
    ),
)

private val EDGE = 22.dp

/**
 * Setup, left-aligned like everything else.
 *
 * The centred-icon-and-paragraph shape is what every onboarding flow looks like, and it
 * has nothing to do with the app behind it. Ranging the text left against the same edge
 * the statement uses means the first screen already teaches you where to look.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pageIndex by remember { mutableIntStateOf(0) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        viewModel.onSmsPermissionResult(granted[Manifest.permission.READ_SMS] == true)
    }

    Box(Modifier.fillMaxSize().background(Ours.ink)) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
            label = "onboarding-step",
            modifier = Modifier.fillMaxSize(),
        ) { step ->
            when (step) {
                OnboardingStep.INTRO -> IntroPages(
                    index = pageIndex,
                    onNext = {
                        if (pageIndex < pages.lastIndex) pageIndex++
                        else viewModel.advanceTo(OnboardingStep.ACCOUNT)
                    },
                )

                OnboardingStep.ACCOUNT -> AccountStep(
                    onContinue = { name -> viewModel.signInLocally(name) },
                )

                OnboardingStep.HOUSEHOLD -> HouseholdStep(
                    inviteSecret = state.inviteSecret,
                    onCreate = { viewModel.createHousehold() },
                    onJoin = { viewModel.joinHousehold(it) },
                    onContinue = { viewModel.advanceTo(OnboardingStep.PERMISSION) },
                )

                OnboardingStep.PERMISSION -> PermissionStep(
                    onGrant = {
                        smsPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.RECEIVE_SMS,
                            )
                        )
                    },
                    onSkip = { viewModel.onSmsPermissionResult(false) },
                )

                OnboardingStep.BACKFILL -> BackfillStep(
                    scanned = state.backfillScanned,
                    total = state.backfillTotal,
                    imported = state.backfillImported,
                    finished = state.backfillFinished,
                    onDone = { viewModel.finish(onFinished) },
                )
            }
        }
    }
}

/**
 * The shared frame: caption at the top, content in the middle, actions pinned bottom.
 *
 * Every step uses it, so the button never moves between screens — a control that jumps
 * position as you advance makes a five-step flow feel like five different apps.
 */
@Composable
private fun Step(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    actions: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = EDGE, vertical = 28.dp),
    ) {
        MicroLabel(label)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Ours.text)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Ours.textSecondary)
}

@Composable
private fun IntroPages(index: Int, onNext: () -> Unit) {
    val page = pages[index]
    Step(
        label = "Ours · ${index + 1} of ${pages.size}",
        content = {
            BiIconView(
                icon = page.icon,
                contentDescription = null,
                tint = Ours.accent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(2.dp))
            Title(page.title)
            Body(page.body)
        },
        actions = {
            // Rules, not dots. Three ticks of a scale you are moving along, which is
            // the same vocabulary the budget ruler uses two screens later.
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier
                            .width(if (i == index) 22.dp else 12.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (i == index) Ours.accent else Ours.hairline)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            AccentButton(
                label = if (index == pages.lastIndex) "Get started" else "Next",
                onClick = onNext,
            )
        },
    )
}

@Composable
private fun AccountStep(onContinue: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Step(
        label = "Your name",
        content = {
            Title("What should we call you?")
            Body("This is the name your partner sees next to your expenses.")
            Spacer(Modifier.height(4.dp))
            HairlineField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Your name",
            )
        },
        actions = {
            AccentButton(
                label = "Continue",
                enabled = name.isNotBlank(),
                onClick = { onContinue(name.trim()) },
            )
        },
    )
}

@Composable
private fun HouseholdStep(
    inviteSecret: String?,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onContinue: () -> Unit,
) {
    var joinCode by remember { mutableStateOf("") }

    if (inviteSecret != null) {
        Step(
            label = "Household",
            content = {
                Title("Household ready")
                Text(
                    text = inviteSecret,
                    style = MaterialTheme.typography.displayMedium,
                    color = Ours.accent,
                    maxLines = 1,
                )
                Body(
                    "Your partner enters this code — or scans the QR in Settings — to " +
                        "join. You can do that later."
                )
            },
            actions = { AccentButton("Continue", onClick = onContinue) },
        )
    } else {
        Step(
            label = "Household",
            content = {
                Title("Set up your household")
                Body("Create one now, or join the one your partner already made.")
                Spacer(Modifier.height(4.dp))
                MicroLabel("Join with a code")
                HairlineField(
                    value = joinCode,
                    onValueChange = { joinCode = it.uppercase() },
                    placeholder = "Invite code",
                )
            },
            actions = {
                AccentButton("Create a new household", onClick = onCreate)
                GhostButton(
                    label = "Join household",
                    onClick = { if (joinCode.trim().length >= 6) onJoin(joinCode.trim()) },
                )
            },
        )
    }
}

@Composable
private fun PermissionStep(onGrant: () -> Unit, onSkip: () -> Unit) {
    Step(
        label = "Permission",
        content = {
            BiIconView(
                icon = BiIcon.Message,
                contentDescription = null,
                tint = Ours.accent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(2.dp))
            Title("Let Ours read your SMS")
            Body(
                "Only bank and UPI messages are parsed, and the text of every message " +
                    "stays on this phone. OTPs are detected and skipped. Nothing is " +
                    "uploaded anywhere in readable form."
            )
            Body("You can skip this and add expenses by hand.")
        },
        actions = {
            AccentButton("Grant permission", onClick = onGrant)
            GhostButton("Skip for now", onClick = onSkip)
        },
    )
}

@Composable
private fun BackfillStep(
    scanned: Int,
    total: Int,
    imported: Int,
    finished: Boolean,
    onDone: () -> Unit,
) {
    Step(
        label = if (finished) "Done" else "Reading",
        content = {
            BiIconView(
                icon = if (finished) BiIcon.Done else BiIcon.Scanning,
                contentDescription = null,
                tint = if (finished) Ours.positive else Ours.accent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(2.dp))
            Title(if (finished) "All caught up" else "Reading your last 6 months")

            if (!finished) {
                Ruler(fraction = if (total > 0) scanned.toFloat() / total else 0f)
                MicroLabel("$scanned of $total messages · $imported found")
            } else {
                Body(
                    // The *total* held, not this run's delta. The backfill can run twice
                    // — once automatically at launch, once when permission is granted —
                    // and the second pass dedupes everything, so reporting its import
                    // count told the user "0 expenses imported" while 171 sat in the
                    // database. Nothing was wrong; the number measured the wrong thing.
                    if (total > 0) {
                        "$total expenses ready, read from $scanned messages."
                    } else {
                        "No bank messages found in the last 6 months."
                    }
                )
            }
        },
        actions = {
            if (finished) AccentButton("See my spending", onClick = onDone)
        },
    )
}

@Composable
private fun HairlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ours.surface)
            .padding(horizontal = 13.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ours.textLabel)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current
                .merge(MaterialTheme.typography.bodyLarge)
                .copy(color = Ours.text),
            cursorBrush = SolidColor(Ours.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
