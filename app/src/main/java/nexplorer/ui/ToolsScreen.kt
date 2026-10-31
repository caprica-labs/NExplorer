package nexplorer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.ui.unit.sp

sealed interface Glyph {
    data class Vector(val icon: ImageVector) : Glyph
    data object Word : Glyph
}

private val PDF = Glyph.Vector(Icons.Outlined.Description)
private val IMAGE = Glyph.Vector(Icons.Outlined.Image)
private val TEXT = Glyph.Vector(Icons.Outlined.TextFields)
private val WORD = Glyph.Word

data class Tool(val label: String, val icon: Glyph, val to: Glyph? = null)

private fun tool(label: String, icon: ImageVector) = Tool(label, Glyph.Vector(icon))

private val PDF_TOOLS = tool("PDF Tools", Icons.Outlined.PictureAsPdf)
private val firstPage = listOf(
    tool("Recycle bin", Icons.Outlined.Delete),
    PDF_TOOLS,
    tool("Network Share", Icons.Outlined.WifiTethering),
    tool("Downloads", Icons.Outlined.Download),
)

private val rows: List<List<Tool>> = listOf(
    listOf(
        tool("Lock PDF", Icons.Outlined.Lock),
        tool("Unlock PDF", Icons.Outlined.NoEncryption),
        tool("Scanner", Icons.Outlined.DocumentScanner),
    ),
    listOf(
        tool("Merge PDFs", Icons.AutoMirrored.Outlined.MergeType),
        tool("Manage PDF", Icons.Outlined.Dashboard),
        tool("Compress", Icons.Outlined.Compress),
    ),
    listOf(
        Tool("PDF to Image", PDF, IMAGE),
        Tool("PDF to Text", PDF, TEXT),
        Tool("PDF to Word", PDF, WORD),
    ),
    listOf(
        Tool("Image to PDF", IMAGE, PDF),
        Tool("Text to PDF", TEXT, PDF),
        Tool("Word to PDF", WORD, PDF),
    ),
)

@Composable
fun ToolsScreen(resetSignal: Int = 0, onTool: (Tool) -> Boolean = { false }) {
    val ctx = LocalContext.current
    var pdfTools by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = pdfTools) { pdfTools = false }
    LaunchedEffect(resetSignal) { if (resetSignal > 0) pdfTools = false }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        if (!pdfTools) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                firstPage.forEach { tool ->
                    ToolCell(tool, Modifier.weight(1f)) {
                        if (tool == PDF_TOOLS) pdfTools = true else onTool(tool)
                    }
                }
            }
            return@Column
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { tool ->
                    ToolCell(tool, Modifier.weight(1f)) {
                        if (!onTool(tool)) Toast.makeText(ctx, "${tool.label}: coming soon", Toast.LENGTH_SHORT).show()
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun ToolCell(tool: Tool, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            val to = tool.to
            if (to == null) {
                GlyphIcon(tool.icon, 28.dp)
            } else {
                GlyphIcon(tool.icon, 26.dp)
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
                )
                GlyphIcon(to, 26.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tool.label,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun GlyphIcon(glyph: Glyph, size: Dp) {
    val color = MaterialTheme.colorScheme.onSurface
    when (glyph) {
        is Glyph.Vector -> Icon(glyph.icon, contentDescription = null, tint = color, modifier = Modifier.size(size))
        Glyph.Word -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(size * 0.72f, size * 0.86f)
                    .border(1.8.dp, color, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("W", color = color, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, lineHeight = (size.value * 0.42f).sp)
            }
        }
    }
}
