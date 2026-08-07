package com.manuel.ours.ui.screens.rules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.domain.model.Category
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.ValueTextStyle


/**
 * Auto-assign rules, one section per category.
 *
 * The rule the app already learns silently every time you sort a merchant is the same
 * rule you can write here by hand. This screen exists so that behaviour stops being
 * invisible: you can see what the app decided on your behalf, correct it, and add the
 * ones it will never guess — a landlord's name means nothing to a parser, but it means
 * "rent" to you every month.
 */
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<Category?>(null) }

    Scaffold(
            // contentWindowInsets = WindowInsets(0): the NavHost already sits inside the
            // outer Scaffold's padding, so consuming system-bar insets again inset every
            // one of these screens twice — most visibly the full-bleed QR viewfinder.
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),modifier = Modifier.imePadding(), containerColor = Ours.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OursTopBar(
                    title = "RULES",
                    onBack = onBack,
                ) {
                    MicroLabel("${state.total} rules")
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Any expense whose payee contains the text is filed here " +
                            "automatically, from now on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceVariant,
                    )
                    // Both of these surprise people, so they are said out loud rather
                    // than left to be discovered.
                    Text(
                        "Matching ignores case and matches anywhere in the name, so " +
                            "\"reliance\" catches \"Reliance Smart\" too. Money coming in " +
                            "is never matched — credits are always Income.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceMuted,
                    )
                }
            }

            items(state.groups, key = { it.category.name }) { group ->
                CategorySection(
                    group = group,
                    expanded = expanded == group.category,
                    onToggle = {
                        expanded = if (expanded == group.category) null else group.category
                    },
                    onAdd = { viewModel.add(it, group.category) },
                    onRemove = viewModel::remove,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySection(
    group: CategoryRules,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (Long) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CategoryAvatar(group.category, size = 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    group.category.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.onSurface,
                )
                MicroLabel(
                    when (val n = group.rules.size) {
                        0 -> "No rules"
                        1 -> "1 rule"
                        else -> "$n rules"
                    }
                )
            }
            Text(
                text = group.rules.count { it.userDefined }.takeIf { it > 0 }?.let { "$it yours" }
                    ?: "",
                style = ValueTextStyle.copy(fontSize = 11.sp),
                color = Ours.primary,
            )
            OursIconView(
                if (expanded) OursIcon.Collapse else OursIcon.Expand,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Ours.onSurfaceMuted,
                modifier = Modifier.size(11.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                if (group.rules.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        group.rules.forEach { rule ->
                            RuleChip(rule = rule, onRemove = { onRemove(rule.id) })
                        }
                    }
                }
                AddRuleField(onAdd = onAdd)
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
    }
}

/**
 * A rule, with its own delete.
 *
 * Rules you wrote are outlined in the accent; the ones that shipped with the app are
 * plain, so "did I do this or did it come like that?" is answerable at a glance.
 */
@Composable
private fun RuleChip(rule: Rule, onRemove: () -> Unit) {
    val edge = if (rule.userDefined) Ours.primary else Ours.outlineVariant
    val fg = if (rule.userDefined) Ours.onSurface else Ours.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, edge, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(rule.pattern, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        OursIconButton(
                        icon = OursIcon.Dismiss,
                        contentDescription = "Remove rule ${rule.pattern}",
                        onClick = onRemove,
                        tint = Ours.onSurfaceMuted,
                        glyph = 9.dp,
                        size = Space.target,
                    )
    }
}

@Composable
private fun AddRuleField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val valid = text.trim().length >= 3

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(Ours.surfaceContainer)
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    "Payee contains…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceMuted,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.bodySmall)
                    .copy(color = Ours.onSurface),
                cursorBrush = SolidColor(Ours.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AccentButton(
            label = "Add rule",
            enabled = valid,
            onClick = {
                onAdd(text.trim())
                text = ""
            },
        )
        if (text.isNotEmpty() && !valid) {
            // Three characters is the floor the repository enforces; saying why beats
            // a button that silently refuses to work.
            MicroLabel("At least 3 characters", color = Ours.warning)
        }
    }
}
