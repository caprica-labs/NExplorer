package nexplorer.ui.reader

import android.util.SizeF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.reader.DarkMode
import nexplorer.reader.TextBox
import nexplorer.ui.theme.AccentBlue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class TextEdit(
    val id: Int,
    val page: Int,
    val rect: TextBox.Rect,
    val rotation: Float,
    val manualH: Float,
    val text: TextFieldValue,
    val size: Float,
    val color: Int,
    val annot: Int?,
)

fun textColor(rgb: FloatArray, dark: Boolean): Color {
    if (!dark) return Color(rgb[0], rgb[1], rgb[2])
    val m = DarkMode.matrix
    fun ch(row: Int) =
        ((m[row * 5] * rgb[0] * 255 + m[row * 5 + 1] * rgb[1] * 255 + m[row * 5 + 2] * rgb[2] * 255 + m[row * 5 + 4]) / 255f)
            .coerceIn(0f, 1f)
    return Color(ch(0), ch(1), ch(2))
}

fun nearestColor(rgb: FloatArray): Int = TextBox.COLORS.indices.minBy { i ->
    val c = TextBox.COLORS[i]
    (c[0] - rgb[0]) * (c[0] - rgb[0]) + (c[1] - rgb[1]) * (c[1] - rgb[1]) + (c[2] - rgb[2]) * (c[2] - rgb[2])
}

private val BAR = 22.dp
private val GRAB = 22.dp

private enum class Part { Move, Resize, Rotate }

data class BoxPos(val rect: TextBox.Rect, val rotation: Float)

fun Modifier.boxGestures(
    box: State<BoxPos?>,
    k: Float,
    pageSize: SizeF,
    barPx: Float,
    grabPx: Float,
    keepShape: Boolean,
    onChange: (BoxPos, resized: Boolean) -> Unit,
): Modifier = pointerInput(k, pageSize, keepShape) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = box.value ?: return@awaitEachGesture
        val r = start.rect
        val f0 = down.position / k
        val cx = r.x + r.w / 2
        val cy = r.y + r.h / 2
        val local = turn(f0.x - cx, f0.y - cy, -start.rotation).let { (x, y) -> Offset(cx + x, cy + y) }
        val bar = barPx / k
        val grab = grabPx / k
        val part = when {
            (local - Offset(r.x + r.w, r.y + r.h)).getDistance() <= grab -> Part.Resize
            (local - Offset(r.x + r.w, r.y - bar)).getDistance() <= grab -> Part.Rotate
            local.x in r.x..(r.x + r.w) && local.y in (r.y - bar)..r.y -> Part.Move
            else -> null
        } ?: return@awaitEachGesture
        down.consume()
        val a0 = Math.toDegrees(atan2((f0.y - cy).toDouble(), (f0.x - cx).toDouble())).toFloat()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val c = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            c.consume()
            val f = c.position / k
            when (part) {
                Part.Move -> onChange(start.copy(rect = TextBox.move(r, f.x - f0.x, f.y - f0.y, pageSize.width, pageSize.height)), false)
                Part.Resize -> onChange(start.copy(rect = TextBox.resizeBottomRight(r, start.rotation, f.x, f.y, TextBox.MIN_H, keepShape)), true)
                Part.Rotate -> {
                    val a = Math.toDegrees(atan2((f.y - cy).toDouble(), (f.x - cx).toDouble())).toFloat()
                    onChange(start.copy(rotation = TextBox.snap(start.rotation + a - a0)), false)
                }
            }
        }
    }
}

private fun turn(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
    val a = Math.toRadians(degrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return (x * c - y * s) to (x * s + y * c)
}

@Composable
fun textBoxHandleSizes(): Pair<Float, Float> = with(LocalDensity.current) { BAR.toPx() to GRAB.toPx() }

@Composable
fun BoxFrame(
    rect: TextBox.Rect,
    rotation: Float,
    k: Float,
    dark: Boolean,
    solid: Boolean,
    onBottom: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val barPx = with(density) { BAR.toPx() }
    val boxW = rect.w * k
    val boxH = rect.h * k
    val pageBg = if (dark) Color.Black else Color.White

    Box(
        Modifier
            .offset { IntOffset((rect.x * k).roundToInt(), (rect.y * k - barPx).roundToInt()) }
            .size(with(density) { boxW.toDp() }, with(density) { (boxH + barPx).toDp() })
            .graphicsLayer {
                rotationZ = rotation
                transformOrigin = TransformOrigin(0.5f, (barPx + boxH / 2) / (barPx + boxH))
            }
            .onGloballyPositioned { coords -> onBottom(coords.boundsInWindow().bottom) },
    ) {
        Box(
            Modifier.fillMaxWidth().height(BAR)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(AccentBlue),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.OpenWith, contentDescription = "Move", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Box(
            Modifier
                .offset { IntOffset(0, barPx.roundToInt()) }
                .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                .background(if (solid) pageBg else pageBg.copy(alpha = 0.85f))
                .border(1.dp, AccentBlue),
        ) {
            content()
        }
        Handle(Offset(boxW, 0f), Icons.Outlined.Sync, "Rotate")
        Handle(Offset(boxW, barPx + boxH), null, "Resize")
    }
}

@Composable
fun TextBoxOverlay(
    edit: TextEdit,
    k: Float,
    dark: Boolean,
    keyboardTop: Float?,
    onChange: (TextEdit) -> Unit,
    onOffscreen: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BoxFrame(
        edit.rect, edit.rotation, k, dark,
        solid = edit.annot != null,
        onBottom = { bottom ->
            val top = keyboardTop
            if (top != null && bottom > top - 8) onOffscreen(bottom - top + 24)
        },
    ) {
        BasicTextField(
            value = edit.text,
            onValueChange = { onChange(edit.copy(text = it)) },
            textStyle = TextStyle(
                color = textColor(TextBox.COLORS[edit.color], dark),
                fontSize = with(density) { (edit.size * k).toSp() },
                lineHeight = with(density) { (edit.size * TextBox.LINE_HEIGHT * k).toSp() },
            ),
            cursorBrush = SolidColor(AccentBlue),
            modifier = Modifier
                .fillMaxSize()
                .padding(with(density) { (TextBox.PAD / 2 * k).toDp() })
                .focusRequester(focus),
        )
    }
}

@Composable
private fun Handle(center: Offset, icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String) {
    val size = 24.dp
    val half = with(LocalDensity.current) { size.toPx() } / 2
    Box(
        Modifier
            .offset { IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt()) }
            .size(size)
            .clip(CircleShape)
            .background(AccentBlue),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(14.dp))
        } else {
            Canvas(Modifier.size(8.dp)) { drawCircle(Color.White) }
        }
    }
}

@Composable
fun TextToolbar(
    edit: TextEdit,
    onChange: (TextEdit) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val i = TextBox.SIZES.indexOf(edit.size).takeIf { it >= 0 } ?: TextBox.SIZES.indexOf(TextBox.DEFAULT_SIZE)
            IconButton(onClick = { onChange(edit.copy(size = TextBox.SIZES[(i - 1).coerceAtLeast(0)])) }) {
                Icon(Icons.Outlined.Remove, contentDescription = "Smaller text")
            }
            Text("${edit.size.roundToInt()} pt", fontSize = 13.sp, modifier = Modifier.width(44.dp))
            IconButton(onClick = { onChange(edit.copy(size = TextBox.SIZES[(i + 1).coerceAtMost(TextBox.SIZES.lastIndex)])) }) {
                Icon(Icons.Outlined.Add, contentDescription = "Bigger text")
            }
            IconButton(onClick = { onChange(edit.copy(rotation = TextBox.rotateClockwise(edit.rotation))) }) {
                Icon(Icons.Outlined.Rotate90DegreesCw, contentDescription = "Rotate 90 degrees clockwise")
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onDone) { Text("Done", color = AccentBlue) }
            }
        }
        ColorRow(edit.color) { onChange(edit.copy(color = it)) }
        Box(Modifier.height(2.dp))
    }
}

@Composable
fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextBox.COLORS.forEachIndexed { idx, rgb ->
            val on = idx == selected
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(rgb[0], rgb[1], rgb[2]))
                    .border(
                        width = if (on) 3.dp else 1.dp,
                        color = if (on) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onPick(idx) },
            )
        }
    }
}
