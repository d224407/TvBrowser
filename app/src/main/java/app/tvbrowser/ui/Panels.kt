package app.tvbrowser.ui

import android.content.Context
import android.app.DownloadManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.tvbrowser.BrowserViewModel
import app.tvbrowser.BuildConfig
import app.tvbrowser.UpdateStatus
import app.tvbrowser.R
import app.tvbrowser.data.Panel
import app.tvbrowser.data.UrlUtils
import app.tvbrowser.web.DownloadItem
import app.tvbrowser.web.Downloads
import app.tvbrowser.web.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PanelHost(vm: BrowserViewModel, mgr: TabManager, onExit: () -> Unit) {
    when (vm.panel) {
        Panel.NONE -> Unit
        Panel.ADDRESS -> AddressPanel(vm)
        Panel.MENU -> MenuPanel(vm, onExit)
        Panel.TABS -> TabsPanel(vm)
        Panel.BOOKMARKS -> BookmarksPanel(vm)
        Panel.HISTORY -> HistoryPanel(vm)
        Panel.DOWNLOADS -> DownloadsPanel()
        Panel.SETTINGS -> SettingsPanel(vm, mgr)
    }
}

@Composable
private fun AddressPanel(vm: BrowserViewModel) {
    val current = vm.active?.url.orEmpty()
    var text by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    PanelFrame(stringResource(R.string.search_hint)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(field),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.navigate(text.text) }),
        )
        LazyColumn(
            Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                if (text.text.isNotBlank()) {
                    TvRow(stringResource(R.string.go), text.text.trim(), Modifier.fillMaxWidth()) { vm.navigate(text.text) }
                }
            }
        }
    }
}

@Composable
private fun MenuPanel(vm: BrowserViewModel, onExit: () -> Unit) {
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    val s = vm.settings
    val items = listOf<Pair<String, () -> Unit>>(
        stringResource(R.string.new_tab) to { vm.newTab() },
        stringResource(R.string.tabs) to { vm.openPanel(Panel.TABS) },
        stringResource(R.string.bookmarks) to { vm.openPanel(Panel.BOOKMARKS) },
        stringResource(R.string.add_bookmark) to { vm.toggleBookmark(); vm.closePanel() },
        stringResource(R.string.history) to { vm.openPanel(Panel.HISTORY) },
        stringResource(R.string.downloads) to { vm.openPanel(Panel.DOWNLOADS) },
        "${stringResource(R.string.cursor_mode)}: ${if (s.cursorMode) on else off}" to
            { vm.updateSettings(s.copy(cursorMode = !s.cursorMode)) },
        "${stringResource(R.string.desktop_mode)}: ${if (s.desktopMode) on else off}" to
            { vm.updateSettings(s.copy(desktopMode = !s.desktopMode)) },
        stringResource(R.string.home) to { vm.goHome() },
        stringResource(R.string.settings) to { vm.openPanel(Panel.SETTINGS) },
        stringResource(R.string.exit) to onExit,
    )
    PanelFrame(stringResource(R.string.menu)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items) { i, (label, action) ->
                TvButton(
                    label,
                    Modifier.fillMaxWidth().then(if (i == 0) Modifier.initialFocus() else Modifier),
                ) { action() }
            }
        }
    }
}

@Composable
private fun TabsPanel(vm: BrowserViewModel) {
    PanelFrame(stringResource(R.string.tabs)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TvButton("＋ " + stringResource(R.string.new_tab), Modifier.fillMaxWidth().initialFocus()) { vm.newTab() }
            }
            items(vm.tabs.toList(), key = { it.id }) { t ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvRow(
                        t.title.ifEmpty { if (t.url.isEmpty()) stringResource(R.string.new_tab) else UrlUtils.display(t.url) },
                        UrlUtils.display(t.url),
                        Modifier.weight(1f),
                    ) { vm.selectTab(t.id) }
                    TvButton("✕") { vm.closeTab(t.id) }
                }
            }
        }
    }
}

@Composable
private fun BookmarksPanel(vm: BrowserViewModel) {
    PanelFrame(stringResource(R.string.bookmarks)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.bookmarks.toList()) { b ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvRow(
                        b.title.ifEmpty { UrlUtils.display(b.url) },
                        b.url,
                        Modifier.weight(1f).initialFocusIf(b == vm.bookmarks.firstOrNull()),
                    ) { vm.navigate(b.url) }
                    TvButton("✕") { vm.removeBookmark(b) }
                }
            }
        }
    }
}

@Composable
private fun HistoryPanel(vm: BrowserViewModel) {
    PanelFrame(stringResource(R.string.history)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TvButton(stringResource(R.string.clear_history), Modifier.fillMaxWidth().initialFocus()) { vm.clearHistory() }
            }
            items(vm.history.toList()) { h ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvRow(
                        h.title.ifEmpty { UrlUtils.display(h.url) },
                        h.url,
                        Modifier.weight(1f),
                    ) { vm.navigate(h.url) }
                    TvButton("✕") { vm.removeHistory(h) }
                }
            }
        }
    }
}

@Composable
private fun DownloadsPanel() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<DownloadItem>()) }
    LaunchedEffect(Unit) {
        while (true) {
            list = withContext(Dispatchers.IO) { Downloads.query(ctx) }
            delay(1000)
        }
    }
    PanelFrame(stringResource(R.string.downloads)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { d ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvRow(d.title, statusText(ctx, d), Modifier.weight(1f).initialFocusIf(d == list.firstOrNull())) {
                        if (d.status == DownloadManager.STATUS_SUCCESSFUL) Downloads.open(ctx, d)
                    }
                    TvButton("✕") {
                        Downloads.remove(ctx, d.id)
                        list = list.filter { it.id != d.id }
                    }
                }
            }
        }
    }
}

private fun statusText(ctx: Context, d: DownloadItem): String = when (d.status) {
    DownloadManager.STATUS_SUCCESSFUL -> ctx.getString(R.string.status_done)
    DownloadManager.STATUS_FAILED -> ctx.getString(R.string.status_failed)
    DownloadManager.STATUS_PAUSED -> ctx.getString(R.string.status_paused)
    DownloadManager.STATUS_PENDING -> ctx.getString(R.string.status_pending)
    else -> if (d.total > 0) "${d.bytes * 100 / d.total}%" else "…"
}

@Composable
private fun SettingsPanel(vm: BrowserViewModel, mgr: TabManager) {
    val s = vm.settings
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    val zooms = listOf(80, 100, 120, 150, 200)
    val rules = if (vm.adBlock.busy) {
        stringResource(R.string.updating)
    } else {
        "${vm.adBlock.ruleCount} ${stringResource(R.string.rules)}"
    }
    val updateValue = when (vm.updateStatus) {
        UpdateStatus.IDLE -> BuildConfig.VERSION_NAME
        UpdateStatus.CHECKING -> "…"
        UpdateStatus.LATEST -> stringResource(R.string.up_to_date)
        UpdateStatus.AVAILABLE -> "v${vm.updateVersion}"
        UpdateStatus.FAILED -> stringResource(R.string.update_failed)
    }
    val rows = listOf<Triple<String, String, () -> Unit>>(
        Triple(stringResource(R.string.search_engine), UrlUtils.engines[s.searchEngine.coerceIn(0, UrlUtils.engines.lastIndex)].first) {
            vm.updateSettings(s.copy(searchEngine = (s.searchEngine + 1) % UrlUtils.engines.size))
        },
        Triple(stringResource(R.string.cursor_mode), if (s.cursorMode) on else off) {
            vm.updateSettings(s.copy(cursorMode = !s.cursorMode))
        },
        Triple(stringResource(R.string.desktop_mode), if (s.desktopMode) on else off) {
            vm.updateSettings(s.copy(desktopMode = !s.desktopMode))
        },
        Triple(stringResource(R.string.text_size), "${s.textZoom}%") {
            val i = zooms.indexOf(s.textZoom)
            vm.updateSettings(s.copy(textZoom = zooms[(i + 1) % zooms.size]))
        },
        Triple(stringResource(R.string.javascript), if (s.javascript) on else off) {
            vm.updateSettings(s.copy(javascript = !s.javascript))
        },
        Triple(stringResource(R.string.adblock), if (s.adBlock) on else off) {
            vm.updateSettings(s.copy(adBlock = !s.adBlock))
        },
        Triple(stringResource(R.string.update_filters), rules) { vm.updateFilters() },
        Triple(stringResource(R.string.history_suggestions), if (s.historySuggestions) on else off) {
            vm.updateSettings(s.copy(historySuggestions = !s.historySuggestions))
        },
        Triple(stringResource(R.string.third_party_cookies), if (s.thirdPartyCookies) on else off) {
            vm.updateSettings(s.copy(thirdPartyCookies = !s.thirdPartyCookies))
        },
        Triple(stringResource(R.string.check_update), updateValue) { vm.checkOrInstallUpdate() },
        Triple(stringResource(R.string.clear_cache), "") { vm.clearCache() },
        Triple(stringResource(R.string.clear_cookies), "") { vm.clearCookies() },
        Triple(stringResource(R.string.clear_history), "") { vm.clearHistory() },
    )
    var sel by remember { mutableIntStateOf(0) }
    val reqs = remember { List(rows.size) { FocusRequester() } }
    LaunchedEffect(s, vm.adBlock.busy, vm.adBlock.ruleCount, vm.updateStatus) {
        runCatching { reqs[sel].requestFocus() }
    }
    PanelFrame(stringResource(R.string.settings)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(rows) { i, (label, value, action) ->
                TvRow(
                    label,
                    value,
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(reqs[i])
                        .onFocusChanged { if (it.isFocused) sel = i },
                ) { action() }
            }
        }
    }
}

@Composable
private fun Modifier.initialFocusIf(condition: Boolean): Modifier =
    if (condition) this.initialFocus() else this
