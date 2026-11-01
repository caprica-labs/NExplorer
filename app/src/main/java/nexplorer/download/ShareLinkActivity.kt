package nexplorer.download

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import nexplorer.PermissionGate
import nexplorer.data.ReaderPrefs
import nexplorer.files.Volumes
import nexplorer.reader.AppTheme
import nexplorer.ui.theme.NExplorerTheme

class ShareLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val link = DownloadNames.linkIn(text)
        if (link == null) {
            Toast.makeText(this, "No web link to download", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Downloads.init(this)
        enableEdgeToEdge()
        val prefs = ReaderPrefs(this)
        setContent {
            val appTheme by prefs.appTheme.collectAsState(initial = AppTheme.FollowSystem)
            val dark = when (appTheme) {
                AppTheme.FollowSystem -> isSystemInDarkTheme()
                AppTheme.Light -> false
                AppTheme.Dark -> true
            }
            val view = LocalView.current
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            NExplorerTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                    PermissionGate {
                        val ctx = LocalContext.current
                        val volumes = remember { Volumes.list(ctx) }
                        AddDownloadDialog(
                            volumes = volumes,
                            initialLink = link,
                            onDismiss = ::finish,
                            onAdded = { started ->
                                Toast.makeText(ctx, if (started) "Downloading" else "Added to Downloads", Toast.LENGTH_SHORT).show()
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }
}
