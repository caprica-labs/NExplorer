package nexplorer.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import nexplorer.reader.Signature
import nexplorer.reader.TextBox
import nexplorer.ui.theme.AccentBlue

data class SignEdit(
    val id: Int,
    val page: Int,
    val rect: TextBox.Rect,
    val rotation: Float,
    val sig: Signature,
    val color: Int,
    val annot: Int?,
)

fun DrawScope.drawStrokes(strokes: List<FloatArray>, width: Float, scale: Float, origin: Offset, color: Color) {
    val pen = Stroke(width = (width * scale).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (s in strokes) {
        val n = s.size / 2
        if (n == 0) continue
        fun at(i: Int) = Offset(origin.x + s[2 * i] * scale, origin.y + s[2 * i + 1] * scale)
        val path = Path().apply {
            val p0 = at(0)
            moveTo(p0.x, p0.y)
            for (i in 1 until n) {
                val a = at(i - 1)
                val b = at(i)
                quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
            }
            val last = at(n - 1)
            lineTo(last.x, last.y)
        }
        drawPath(path, color, style = pen)
    }
}

@Composable
fun SignaturePreview(sig: Signature, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / sig.w, size.height / sig.h)
        val origin = Offset((size.width - sig.w * scale) / 2, (size.height - sig.h * scale) / 2)
        drawStrokes(sig.strokes, sig.width, scale, origin, color)
    }
}

@Composable
fun SignBoxOverlay(edit: SignEdit, k: Float, dark: Boolean) {
    BoxFrame(edit.rect, edit.rotation, k, dark, solid = edit.annot != null) {
        Canvas(Modifier.fillMaxSize()) {
            drawStrokes(edit.sig.strokes, edit.sig.width, size.width / edit.sig.w, Offset.Zero, textColor(TextBox.COLORS[edit.color], dark))
        }
    }
}

@Composable
fun SignToolbar(
    edit: SignEdit,
    onChange: (SignEdit) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
