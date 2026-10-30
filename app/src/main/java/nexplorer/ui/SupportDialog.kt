package nexplorer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.ui.theme.AccentBlue

data class Coin(val name: String, val note: String?, val address: String)

object Donations {
    val coins: List<Coin> = listOf(
        Coin(
            "Monero", null,
            "85EsC2TnGfXM3sP83sPjuzSzYorq7C6yAHUXktqPrkxA3wa4pcuNae3PhMjiFeLvyXd5g5GT7eesB7wCHKQ5yboUPNTWAoR",
        ),
        Coin("Bitcoin", null, "bc1q6j6ekxmcsytsgaqyu8896ula899cv5pxem6f4m"),
        Coin("TON", "also USDT on TON", "UQBP8HuLo2LiDXERhsRw2ho7p85bUliECj-g6K55rWAH0Oky"),
        Coin("Tron", "also USDT (TRC-20)", "TN9qoiCKpFMMQMkXQN2AFatBfw9xhVpDt8"),
    ).filter { it.address.isNotBlank() }
}

@Composable
fun SupportDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    val coin = Donations.coins.firstOrNull { it.name == shown }
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Support NExplorer") },
        text = {
            Column {
                Text(
                    "If you're enjoying NExplorer, you can support us.",
                    color = gray,
                )
                Column(Modifier.padding(top = 12.dp)) {
                    Donations.coins.forEach { c ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontSize = 15.sp)
                                c.note?.let { Text(it, fontSize = 12.sp, color = gray) }
                            }
                            TextButton(onClick = { shown = c.name }) { Text("Reveal", color = AccentBlue) }
                        }
                    }
                }
                Column(
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (coin == null) {
                        Text("Tap Reveal to show a wallet address.", fontSize = 13.sp, color = gray)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(coin.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { copy(ctx, coin) }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy address", tint = AccentBlue)
                            }
                        }
                        SelectionContainer {
                            Text(coin.address, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = AccentBlue) } },
    )
}

private fun copy(ctx: Context, coin: Coin) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("${coin.name} address", coin.address))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(ctx, "${coin.name} address copied", Toast.LENGTH_SHORT).show()
    }
}
