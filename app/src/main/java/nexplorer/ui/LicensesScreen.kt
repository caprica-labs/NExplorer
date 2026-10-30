package nexplorer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nexplorer.ui.theme.AccentBlue

fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()?.let { "v$it" }.orEmpty()

fun openLink(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(ctx, "No app here can open web links", Toast.LENGTH_SHORT).show() }
}

@Composable
fun LicensesScreen() {
    val ctx = LocalContext.current
    var reading by rememberSaveable { mutableStateOf<Licence?>(null) }
    val gray = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Licenses", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        // Our own licence.
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("NExplorer ${appVersion(ctx)}".trim(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Copyright © ${Credits.YEAR} ${Credits.OWNER}", fontSize = 13.sp)
            Text(
                "NExplorer is an open-source application.\n\n" +
                    "It is licensed under the terms of the GNU Affero General Public License, version 3 or " +
                    "(at your option) any later version.\n\n" +
                    "Anyone may extend or fork it, as long as they credit the author, ${Credits.OWNER}, " +
                    "and keep it under the same licence.\n\n" +
                    "It comes with no warranty of any kind.",
                fontSize = 13.sp,
                color = gray,
            )
            Link("Source code: github.com/caprica-labs/NExplorer") { openLink(ctx, Credits.SOURCE) }
            Link("Read the licence") { reading = Licence.AGPL }
        }

        Text(
            "NExplorer is made with these libraries. Thank you to everyone who wrote them.",
            fontSize = 13.sp,
            color = gray,
            modifier = Modifier.padding(top = 18.dp),
        )
        Credits.groups.forEach { (group, credits) ->
            OptionLabel(group)
            credits.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                CreditRow(c) { reading = c.licence }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    reading?.let { LicenceText(it) { reading = null } }
}

@Composable
private fun CreditRow(c: Credit, onLicence: () -> Unit) {
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(c.what, fontSize = 12.sp, color = gray)
        Text(c.copyright, fontSize = 12.sp)
        c.notice?.let { Text(it, fontSize = 12.sp, fontStyle = FontStyle.Italic) }
        Link(c.licence.title, onLicence)
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = AccentBlue,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun LicenceText(licence: Licence, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember(licence) {
        runCatching { reflow(ctx.assets.open("licenses/${licence.file}").bufferedReader().use { it.readText() }) }
            .getOrDefault("The licence text couldn't be read.")
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(licence.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Done", color = AccentBlue) }
                }
                Text(
                    text,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                )
            }
        }
    }
}
