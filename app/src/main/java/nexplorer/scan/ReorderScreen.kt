package nexplorer.scan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.ui.ReorderGrid
import nexplorer.ui.theme.AccentBlue

@Composable
fun ReorderScreen(pages: List<ScanPage>, onSave: (List<ScanPage>) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var order by remember { mutableStateOf(pages) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Reorder", fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onSave(order) }) { Text("Save", color = AccentBlue, fontSize = 16.sp) }
        }
        Text(
            "Hold a page and drag it to a new place.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ReorderGrid(
            items = order,
            key = { it.id },
            onMove = { from, to -> order = Lists.move(order, from, to) },
            modifier = Modifier.fillMaxSize().padding(8.dp),
        ) { page, i, lifted ->
            Box(
                Modifier
                    .padding(6.dp)
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(6.dp))
                    .border(if (lifted) 2.dp else 0.5.dp, if (lifted) AccentBlue else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                PagePreview(page, 400, Modifier.fillMaxSize())
                NumberBadge(i + 1, Modifier.align(Alignment.BottomCenter).padding(6.dp))
            }
        }
    }
}

@Composable
fun NumberBadge(n: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(26.dp).clip(CircleShape).background(AccentBlue), contentAlignment = Alignment.Center) {
        Text("$n", color = Color.White, fontSize = 12.sp)
    }
}
