package app.tvbrowser.ui

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tvbrowser.BrowserViewModel
import app.tvbrowser.PageInput
import app.tvbrowser.R
import app.tvbrowser.data.Panel
import app.tvbrowser.data.UrlUtils
import app.tvbrowser.web.TabManager

@Composable
fun BrowserRoot(vm: BrowserViewModel, mgr: TabManager, input: PageInput, onExit: () -> Unit) {
    val tab = vm.active
    val home = tab == null || tab.url.isEmpty()
    val overlay = vm.panel != Panel.NONE || vm.chromeVisible

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (home) {
            if (vm.panel == Panel.NONE) HomeScreen(vm, mgr)
        } else if (tab != null) {
            WebViewContainer(tab, mgr, blockFocus = overlay)
            if (tab.loading && tab.progress < 100) {
                Box(
                    Modifier
                        .fillMaxWidth(tab.progress / 100f)
                        .height(3.dp)
                        .background(Accent),
                )
            }
            if (vm.chromeVisible && vm.panel == Panel.NONE) {
                Toolbar(vm, mgr, isHome = false, modifier = Modifier.align(Alignment.TopCenter))
            }
            if (!overlay && vm.settings.cursorMode && input.cx >= 0f) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(input.cx, input.cy)
                    drawCircle(Color.Black, 12.dp.toPx(), c)
                    drawCircle(Color.White, 9.dp.toPx(), c)
                }
            }
        }
        PanelHost(vm, mgr, onExit)
    }
}

@Composable
fun WebViewContainer(tab: app.tvbrowser.data.TabState, mgr: TabManager, blockFocus: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> FrameLayout(ctx) },
        update = { c ->
            @Suppress("UNUSED_VARIABLE")
            val gen = tab.generation
            val wv = mgr.webViewFor(tab)
            if (wv.parent !== c) {
                (c.getChildAt(0) as? WebView)?.onPause()
                c.removeAllViews()
                (wv.parent as? ViewGroup)?.removeView(wv)
                c.addView(
                    wv,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                wv.onResume()
            }
            c.descendantFocusability =
                if (blockFocus) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (!blockFocus) wv.requestFocus()
        },
    )
}

@Composable
fun Toolbar(vm: BrowserViewModel, mgr: TabManager, isHome: Boolean, modifier: Modifier = Modifier) {
    val tab = vm.active
    val focusKey = vm.panel
    val address = remember { FocusRequester() }
    LaunchedEffect(focusKey) {
        if (vm.panel == Panel.NONE) runCatching { address.requestFocus() }
    }
    val label = when {
        tab == null || tab.url.isEmpty() -> stringResource(R.string.search_hint)
        tab.title.isNotEmpty() -> tab.title
        else -> UrlUtils.display(tab.url)
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xEB101620))
            .padding(horizontal = 48.dp, vertical = 14.dp)
            .onPreviewKeyEvent { e ->
                if (!isHome && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                    vm.hideChrome()
                    true
                } else {
                    false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvButton("◀", enabled = tab?.canBack == true) { mgr.goBack() }
        TvButton("▶", enabled = tab?.canForward == true) { mgr.goForward() }
        TvButton(
            if (tab?.loading == true) "✕" else "⟳",
            enabled = tab != null && tab.url.isNotEmpty(),
        ) { tab?.let { mgr.reloadOrStop(it) } }
        TvButton(label, Modifier.weight(1f).focusRequester(address)) { vm.openPanel(Panel.ADDRESS) }
        TvButton("▢ ${vm.tabs.size}") { vm.openPanel(Panel.TABS) }
        TvButton(
            if (tab != null && vm.isBookmarked(tab.url)) "★" else "☆",
            enabled = tab != null && tab.url.isNotEmpty(),
        ) { vm.toggleBookmark() }
        TvButton("☰") { vm.openPanel(Panel.MENU) }
    }
}

@Composable
fun HomeScreen(vm: BrowserViewModel, mgr: TabManager) {
    val recent = vm.history.distinctBy { UrlUtils.host(it.url) }.take(8)
    Column(Modifier.fillMaxSize()) {
        Toolbar(vm, mgr, isHome = true)
        Column(Modifier.padding(horizontal = 48.dp, vertical = 27.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(vm.bookmarks.toList()) { b ->
                    TvRow(b.title.ifEmpty { UrlUtils.display(b.url) }, UrlUtils.display(b.url)) { vm.navigate(b.url) }
                }
                items(recent) { h ->
                    TvRow(h.title.ifEmpty { UrlUtils.display(h.url) }, UrlUtils.display(h.url)) { vm.navigate(h.url) }
                }
            }
        }
    }
}
