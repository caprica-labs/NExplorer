package nexplorer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.data.ReaderPrefs
import nexplorer.files.RecycleBin
import nexplorer.files.Volumes
import nexplorer.jobs.FileJobs
import nexplorer.reader.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val LICENSES = Tool("Licenses", Glyph.Vector(Icons.Outlined.Gavel))
private val CONNECT = Tool("Connect", Glyph.Vector(Icons.Outlined.Sync))
private val SUPPORT = Tool("Support", Glyph.Vector(Icons.Outlined.FavoriteBorder))

private val links = listOf(LICENSES, CONNECT, SUPPORT)

@Composable
fun SettingsScreen(prefs: ReaderPrefs, resetSignal: Int = 0) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val appTheme by prefs.appTheme.collectAsState(initial = AppTheme.FollowSystem)
    val bin by prefs.recycleBin.collectAsState(initial = false)
    var licenses by rememberSaveable { mutableStateOf(false) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var support by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = licenses) { licenses = false }
    LaunchedEffect(resetSignal) { if (resetSignal > 0) licenses = false }

    if (connect) ConnectDialog { connect = false }
    if (support) SupportDialog { support = false }
    if (licenses) {
        LicensesScreen()
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            OptionLabel("App theme")
            Segments(listOf("Follow system", "Light", "Dark"), selected = appTheme.ordinal) {
                scope.launch { prefs.setAppTheme(AppTheme.entries[it]) }
            }
            OptionLabel("Recycle bin")
            Segments(listOf("Off", "On"), selected = if (bin) 1 else 0) { pick ->
                val on = pick == 1
                if (on == bin) return@Segments
                scope.launch {
                    prefs.setRecycleBin(on)
                    if (!on) {
                        val roots = Volumes.list(ctx).map { it.root }
                        val inBin = withContext(Dispatchers.IO) { RecycleBin.items(roots) }
                        if (inBin.isNotEmpty()) FileJobs.deleteForever(ctx, inBin, title = "Clearing the recycle bin")
                    }
                }
            }
            Text(
                "Deleted files are kept for ${RecycleBin.DAYS} days. Turning it off empties it.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                links.forEach { t ->
                    ToolCell(t, Modifier.weight(1f)) {
                        when (t) {
                            LICENSES -> licenses = true
                            CONNECT -> connect = true
                            SUPPORT -> if (Donations.coins.isNotEmpty()) support = true
                                else Toast.makeText(ctx, "Support: coming soon", Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(ctx, "${t.label}: coming soon", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
