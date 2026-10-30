package nexplorer.textpdf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.reader.TextBox
import nexplorer.ui.reader.textColor
import nexplorer.ui.theme.AccentBlue
import kotlin.math.roundToInt

private const val SCREEN_SCALE = 4f / 3f

@Composable
fun NotepadScreen(title: String, initial: RichText, baseline: RichText, onDone: (RichText) -> Unit, onBack: () -> Unit) {
    var rich by remember { mutableStateOf(initial) }
    var value by remember { mutableStateOf(TextFieldValue(initial.text, TextRange(0))) }
    var typing by remember { mutableStateOf(initial.styleBefore(0) ?: Style()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val changed = rich != baseline
    fun back() { if (changed && rich.text.isNotBlank()) confirmDiscard = true else onBack() }
    BackHandler(onBack = ::back)

    val sel = value.selection
    val hasSelection = !sel.collapsed
    val shown = if (hasSelection) rich.stylesIn(sel.min, sel.max).firstOrNull() ?: typing else typing

    fun change(f: (Style) -> Style) {
        if (hasSelection) rich = rich.restyle(sel.min, sel.max, f) else typing = f(typing)
    }
    fun toggle(all: (Style) -> Boolean, set: Style.(Boolean) -> Style) {
        val on = if (hasSelection) rich.stylesIn(sel.min, sel.max).all(all) else all(typing)
        change { it.set(!on) }
    }
    fun step(by: Int) = change { s ->
        val i = TextBox.SIZES.indexOf(s.size).takeIf { it >= 0 } ?: TextBox.SIZES.indexOf(TextBox.DEFAULT_SIZE)
        s.copy(size = TextBox.SIZES[(i + by).coerceIn(0, TextBox.SIZES.lastIndex)])
    }

    val styled = remember(rich, dark) { annotated(rich, dark) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDone(rich) }, enabled = rich.text.isNotBlank()) {
                Text("Done", color = if (rich.text.isNotBlank()) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            if (value.text.isEmpty()) {
                Text("Start writing…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = (TextBox.DEFAULT_SIZE * SCREEN_SCALE).sp)
            }
            BasicTextField(
                value = value,
                onValueChange = { nv ->
                    if (nv.text != rich.text) {
                        rich = rich.edit(nv.text, typing)
                    } else if (nv.selection != value.selection && nv.selection.collapsed) {
                        typing = rich.styleBefore(nv.selection.start) ?: typing
                    }
                    value = nv
                },
                textStyle = TextStyle(
                    color = textColor(TextBox.COLORS[TextBox.DEFAULT_COLOR], dark),
                    fontSize = (TextBox.DEFAULT_SIZE * SCREEN_SCALE).sp,
                    textDirection = TextDirection.Content,
                ),
                cursorBrush = SolidColor(AccentBlue),
                visualTransformation = { text ->
                    TransformedText(if (text.text == styled.text) styled else text, OffsetMapping.Identity)
                },
                modifier = Modifier.fillMaxSize().focusRequester(focus),
            )
        }
        StyleBar(shown, onSmaller = { step(-1) }, onBigger = { step(1) },
            onBold = { toggle({ it.bold }) { copy(bold = it) } },
            onUnderline = { toggle({ it.underline }) { copy(underline = it) } },
            onColor = { c -> change { it.copy(color = c) } })
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this text?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep writing") } },
        )
    }
}

private fun annotated(rich: RichText, dark: Boolean): AnnotatedString = buildAnnotatedString {
    append(rich.text)
    var pos = 0
    for (r in rich.runs) {
        val s = r.style
        addStyle(
            SpanStyle(
                color = textColor(TextBox.COLORS[s.color], dark),
                fontSize = (s.size * SCREEN_SCALE).sp,
                fontWeight = if (s.bold) FontWeight.Bold else null,
                textDecoration = if (s.underline) TextDecoration.Underline else null,
            ),
            pos, pos + r.length,
        )
        pos += r.length
    }
}

@Composable
private fun StyleBar(
    style: Style,
    onSmaller: () -> Unit,
    onBigger: () -> Unit,
    onBold: () -> Unit,
    onUnderline: () -> Unit,
    onColor: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSmaller) { Icon(Icons.Outlined.Remove, contentDescription = "Smaller text") }
            Text("${style.size.roundToInt()} pt", fontSize = 13.sp, modifier = Modifier.width(44.dp))
            IconButton(onClick = onBigger) { Icon(Icons.Outlined.Add, contentDescription = "Bigger text") }
            Toggle(Icons.Outlined.FormatBold, "Bold", style.bold, onBold)
            Toggle(Icons.Outlined.FormatUnderlined, "Underline", style.underline, onUnderline)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextBox.COLORS.forEachIndexed { idx, rgb ->
                val selected = idx == style.color
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(rgb[0], rgb[1], rgb[2]))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable { onColor(idx) },
                )
            }
        }
        Box(Modifier.height(2.dp))
    }
}

@Composable
private fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                .background(if (on) AccentBlue.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (on) AccentBlue else MaterialTheme.colorScheme.onSurface)
        }
    }
}
