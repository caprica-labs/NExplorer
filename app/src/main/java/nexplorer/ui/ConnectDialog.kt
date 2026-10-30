package nexplorer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.ui.theme.AccentBlue

@Composable
fun ConnectDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Happy to hear any suggestions, ideas, or problems you run into. " +
                        "Send an email, or open an issue on GitHub.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContactRow(Icons.Outlined.Email, Credits.EMAIL) { email(ctx) }
                ContactRow(Icons.Outlined.Code, "github.com/caprica-labs/NExplorer") { openLink(ctx, Credits.SOURCE) }
                Text(
                    "NExplorer ${appVersion(ctx)}".trim(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = AccentBlue) } },
    )
}

@Composable
private fun ContactRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Text(text, color = AccentBlue, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun email(ctx: Context) {
    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Credits.EMAIL}"))
        .putExtra(Intent.EXTRA_SUBJECT, "NExplorer ${appVersion(ctx)}".trim())
    runCatching { ctx.startActivity(mail) }.onFailure {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Email", Credits.EMAIL))
        Toast.makeText(ctx, "Email address copied", Toast.LENGTH_SHORT).show()
    }
}
