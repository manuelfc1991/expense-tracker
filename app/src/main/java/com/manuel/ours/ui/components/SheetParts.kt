package com.manuel.ours.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manuel.ours.ui.theme.Ours

/**
 * A line to write on, not a box to fill in.
 *
 * The filled rounded input this replaces read as the heaviest thing on the sheet —
 * heavier than the amount, which is the one field that actually matters. A rule under
 * the text weighs almost nothing and still says "you can type here", which is the whole
 * job. It is also what the rest of the app already looks like: every value on the detail
 * screen sits on a hairline.
 *
 * [tag] is the small accent word at the right end. It changes to [filledTag] once
 * something is typed, so the line says OPTIONAL while it is empty and EDIT or NOTE once
 * it is not — the field labels itself instead of needing a caption above it.
 */
@Composable
fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
    filledTag: String = tag,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ours.onSurfaceMuted,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = keyboardOptions,
                    textStyle = LocalTextStyle.current
                        .merge(MaterialTheme.typography.bodyLarge)
                        .copy(color = Ours.onSurface),
                    cursorBrush = SolidColor(Ours.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MicroLabel(
                text = if (value.isEmpty()) tag else filledTag,
                color = Ours.primary,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
    }
}

/**
 * The same line, but tapped rather than typed into.
 *
 * The capture sheet cannot host live text fields: it is a bottom sheet that appears
 * unbidden over whatever you were reading, and raising a keyboard inside it would shove
 * the whole sheet up the screen the moment it arrived. So naming and noting open a
 * dialog instead — but the line they open from has to look identical to the one on the
 * add sheet, because to the person using it there is no difference between the two.
 */
@Composable
fun SheetTapRow(
    text: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** False draws [text] as a prompt; true draws it as an answer. */
    filled: Boolean = false,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (filled) Ours.onSurface else Ours.onSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MicroLabel(text = tag, color = Ours.primary)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
    }
}
