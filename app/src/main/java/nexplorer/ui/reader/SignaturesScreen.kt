package nexplorer.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.data.SignatureStore
import nexplorer.reader.Signature
import nexplorer.reader.Signatures
import nexplorer.reader.TextBox
import nexplorer.ui.ToolScreen
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SignaturesScreen(onPick: (Signature) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SignatureStore(ctx) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Signature>?>(null) }
    var drawing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Signature?>(null) }
    suspend fun reload() { list = withContext(Dispatchers.IO) { store.list() } }
    LaunchedEffect(Unit) { reload() }
    BackHandler { if (drawing) drawing = false else onClose() }

    ToolScreen {
        if (drawing) {
            DrawPage(
                nextName = "Signature ${(list?.size ?: 0) + 1}",
                onSave = { sig ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.save(sig) }
                        reload()
                        drawing = false
                    }
                },
                onBack = { drawing = false },
            )
            return@ToolScreen
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Signatures", fontSize = 18.sp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            val l = list
            when {
                l == null -> Spacer(Modifier.weight(1f))
                l.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No saved signatures yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                        items(l, key = { it.id }) { sig ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .combinedClickable(onClick = { onPick(sig) }, onLongClick = { editing = sig })
                                    .padding(12.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)) {
                                    val rgb = TextBox.COLORS[sig.color]
                                    SignaturePreview(sig, Color(rgb[0], rgb[1], rgb[2]), Modifier.fillMaxSize())
                                }
                                Text(sig.name, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    Text(
                        "Tap a signature to place it. Long-press to rename or delete it.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            Button(
                onClick = { drawing = true },
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("New signature") }
        }
    }

    editing?.let { sig ->
        NameDialog(
            title = "Signature",
            start = sig.name,
            onSave = { name ->
                editing = null
                scope.launch { withContext(Dispatchers.IO) { store.save(sig.copy(name = name.ifBlank { sig.name })) }; reload() }
            },
            onDelete = {
                editing = null
                scope.launch { withContext(Dispatchers.IO) { store.delete(sig.id) }; reload() }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun DrawPage(nextName: String, onSave: (Signature) -> Unit, onBack: () -> Unit) {
    val density = LocalDensity.current
    var widthDp by remember { mutableFloatStateOf(3f) }
    var color by remember { mutableIntStateOf(TextBox.DEFAULT_COLOR) }
    val strokes = remember { mutableStateListOf<FloatArray>() }
    val current = remember { mutableStateListOf<Offset>() }
    var askName by remember { mutableStateOf(false) }
    val widthPx = with(density) { widthDp.dp.toPx() }
    val rgb = TextBox.COLORS[color]
    val ink = Color(rgb[0], rgb[1], rgb[2])

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("New signature", fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { askName = true }, enabled = strokes.isNotEmpty()) {
                Text("Save", color = if (strokes.isNotEmpty()) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Width", fontSize = 14.sp)
            Slider(
                value = widthDp,
                onValueChange = { widthDp = it },
                valueRange = 1f..12f,
                colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue, inactiveTrackColor = AccentBlue.copy(alpha = 0.2f)),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            // The pen's actual size.
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(widthDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            if (strokes.isEmpty() && current.isEmpty()) {
                Text("Sign here", color = Color(0xFFBDBDBD), fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            }
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        current.clear()
                        current.add(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            if ((c.position - current.last()).getDistance() >= 1.5f) current.add(c.position)
                        }
                        strokes.add(FloatArray(current.size * 2) { i -> if (i % 2 == 0) current[i / 2].x else current[i / 2].y })
                        current.clear()
                    }
                },
            ) {
                // A faint line to sign on.
                val y = size.height * 0.72f
                drawLine(Color(0xFFE0E0E0), Offset(size.width * 0.08f, y), Offset(size.width * 0.92f, y), strokeWidth = 2f)
                drawStrokes(strokes, widthPx, 1f, Offset.Zero, ink)
                if (current.isNotEmpty()) {
                    drawStrokes(listOf(FloatArray(current.size * 2) { i -> if (i % 2 == 0) current[i / 2].x else current[i / 2].y }), widthPx, 1f, Offset.Zero, ink)
                }
            }
        }
        ColorRow(color) { color = it }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Text("Undo") }
            TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
        }
    }

    if (askName) {
        NameDialog(
            title = "Name this signature",
            start = nextName,
            onSave = { name ->
                askName = false
                Signatures.fromDrawing(SignatureStore.newId(), name.ifBlank { nextName }, color, widthPx, strokes.toList())?.let(onSave)
            },
            onDelete = null,
            onDismiss = { askName = false },
        )
    }
}
