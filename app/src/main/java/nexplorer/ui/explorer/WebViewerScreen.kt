package nexplorer.ui.explorer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import nexplorer.data.ReaderPrefs
import nexplorer.ui.ToolScreen
import nexplorer.ui.reader.ReaderSearchBar
import nexplorer.ui.theme.AccentBlue
import nexplorer.wordpdf.Printing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val DARK_JS = """(function(){if(document.getElementById('nx-dark'))return;
var s=document.createElement('style');s.id='nx-dark';
s.textContent='html{filter:invert(1) hue-rotate(180deg);background:#fff}img,video,picture,canvas,svg image,[style*="background-image"]{filter:invert(1) hue-rotate(180deg)}';
(document.head||document.documentElement).appendChild(s);})()"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewerScreen(file: File, dark: Boolean, prefs: ReaderPrefs, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var counter by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<android.net.Uri?>(null) }

    val web = remember {
        val darkOwn = dark && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        PageView(if (darkOwn) ContextThemeWrapper(ctx, android.R.style.Theme_Material) else ctx).apply {
            setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
            if (darkOwn) WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                blockNetworkLoads = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            setFindListener { at, count, _ -> counter = if (count == 0) "0 of 0" else "${at + 1} of $count" }
            var restored = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "file" || url.scheme == "about" || url.scheme == "data") return false
                    if (request.hasGesture()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url)) } else asking = url
                    return true
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    if (dark && !darkOwn) view.evaluateJavascript(DARK_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (dark && !darkOwn) view.evaluateJavascript(DARK_JS, null)
                    if (restored) return
                    restored = true
                    scope.launch {
                        val at = prefs.position(file.path)
                        if (at > 0f) scrollToFraction(at)
                    }
                }
            }
            home = android.net.Uri.fromFile(file).toString()
            loadUrl(home)
        }
    }

    DisposableEffect(Unit) { onDispose { web.destroy() } }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            val at = web.homeFraction
            CoroutineScope(Dispatchers.IO + NonCancellable).launch { prefs.savePosition(file.path, at) }
        }
    }

    fun closeFind() {
        finding = false
        query = ""
        counter = ""
        web.clearMatches()
    }

    BackHandler {
        when {
            finding -> closeFind()
            web.canGoBack() -> web.goBack()
            else -> onClose()
        }
    }

    ToolScreen {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text(
                    file.name,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { finding = true }) { Icon(Icons.Outlined.Search, contentDescription = "Find in page") }
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Menu, contentDescription = "Menu") }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text("Print / Save as PDF") },
                            onClick = { open = false; Printing.webPage(ctx, web, file.nameWithoutExtension) },
                        )
                    }
                }
            }
            AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
            if (finding) {
                ReaderSearchBar(
                    query = query,
                    counter = counter,
                    onQuery = {
                        query = it
                        if (it.isEmpty()) { web.clearMatches(); counter = "" } else web.findAllAsync(it)
                    },
                    onSubmit = { web.findNext(true) },
                    onPrev = { web.findNext(false) },
                    onNext = { web.findNext(true) },
                    onClose = ::closeFind,
                )
            }
        }
    }

    asking?.let { url ->
        LaunchedEffect(url) {
            delay(3_000)
            asking = null
        }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("This page tried to open something by itself") },
            text = { Text("$url\n\nOpen it?", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                }) { Text("Yes", color = AccentBlue) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("No", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

private class PageView(ctx: Context) : WebView(ctx) {
    var home = ""
    var homeFraction = 0f
        private set

    private fun scrollable() = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (url == home) homeFraction = scrollable().let { if (it == 0) 0f else t.toFloat() / it }
    }

    fun scrollToFraction(f: Float, tries: Int = 30) {
        if (scrollable() > 0) scrollTo(scrollX, (f * scrollable()).toInt())
        else if (tries > 0) postDelayed({ scrollToFraction(f, tries - 1) }, 100)
    }
}
