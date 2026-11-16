package app.tvbrowser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import app.tvbrowser.adblock.AdBlockEngine
import app.tvbrowser.data.Bookmark
import app.tvbrowser.data.HistoryItem
import app.tvbrowser.data.Panel
import app.tvbrowser.data.Settings
import app.tvbrowser.data.Store
import app.tvbrowser.data.TabSnap
import app.tvbrowser.data.TabState
import app.tvbrowser.data.UrlUtils
import app.tvbrowser.web.TabManager

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val adBlock: AdBlockEngine = (app as TvBrowserApp).adBlock

    val tabs = mutableStateListOf<TabState>()
    var activeId by mutableLongStateOf(-1L)
        private set
    var panel by mutableStateOf(Panel.NONE)
        private set
    var chromeVisible by mutableStateOf(false)
        private set
    var settings by mutableStateOf(store.loadSettings())
        private set
    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryItem>()

    private var mgr: TabManager? = null
    private var idSeq = System.currentTimeMillis()

    val active: TabState? get() = tabs.firstOrNull { it.id == activeId }

    init {
        if (store.hasBookmarks()) {
            bookmarks.addAll(store.loadBookmarks())
        } else {
            bookmarks.addAll(
                listOf(
                    Bookmark("Google", "https://www.google.com"),
                    Bookmark("YouTube", "https://www.youtube.com"),
                    Bookmark("Wikipedia", "https://www.wikipedia.org"),
                    Bookmark("DuckDuckGo", "https://duckduckgo.com"),
                ),
            )
            store.saveBookmarks(bookmarks)
        }
        history.addAll(store.loadHistory())

        val (snaps, last) = store.loadTabs()
        snaps.forEach { tabs.add(TabState(it.id, it.url, it.title)) }
        if (tabs.isEmpty()) newTab()
        activeId = if (tabs.any { it.id == last }) last else tabs.first().id
        adBlock.load(app)
    }

    fun attach(m: TabManager) {
        mgr = m
        m.applySettingsToAll(false)
    }

    fun detach() {
        mgr = null
    }

    // ---- panels / chrome ----

    fun openPanel(p: Panel) {
        panel = p
        chromeVisible = false
    }

    fun closePanel() {
        panel = Panel.NONE
    }

    fun showChrome() {
        chromeVisible = true
    }

    fun hideChrome() {
        chromeVisible = false
    }

    // ---- tabs ----

    fun tabById(id: Long): TabState? = tabs.firstOrNull { it.id == id }

    fun newTab(url: String = "") {
        val t = TabState(++idSeq, url)
        tabs.add(t)
        activeId = t.id
        panel = Panel.NONE
        chromeVisible = false
        persistTabs()
    }

    fun selectTab(id: Long) {
        if (tabs.none { it.id == id }) return
        activeId = id
        panel = Panel.NONE
        chromeVisible = false
        persistTabs()
    }

    fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        mgr?.destroy(id)
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            newTab()
            return
        }
        if (activeId == id) activeId = tabs[idx.coerceAtMost(tabs.lastIndex)].id
        persistTabs()
    }

    fun goHome() {
        val t = active ?: return
        mgr?.destroy(t.id)
        t.url = ""
        t.title = ""
        t.canBack = false
        t.canForward = false
        t.loading = false
        panel = Panel.NONE
        chromeVisible = false
        persistTabs()
    }

    fun navigate(input: String) {
        val url = UrlUtils.resolve(input, settings.searchEngine)
        if (url.isEmpty()) return
        val tab = active ?: return
        panel = Panel.NONE
        chromeVisible = false
        val m = mgr
        if (m != null) m.load(tab, url) else tab.url = url
    }

    fun persistTabs() {
        store.saveTabs(tabs.map { TabSnap(it.id, it.url, it.title) }, activeId)
    }

    /** Returns true when the event was consumed; false means the app should exit. */
    fun handleBack(): Boolean {
        if (panel != Panel.NONE) {
            closePanel()
            return true
        }
        val tab = active
        if (tab == null || tab.url.isEmpty()) return false
        if (!chromeVisible) {
            showChrome()
            return true
        }
        val wv = mgr?.activeWebView()
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return true
        }
        if (tabs.size > 1) {
            closeTab(tab.id)
        } else {
            goHome()
        }
        return true
    }

    // ---- history / bookmarks ----

    fun recordVisit(url: String, title: String) {
        val now = System.currentTimeMillis()
        val first = history.firstOrNull()
        if (first != null && first.url == url) {
            history[0] = first.copy(title = title.ifEmpty { first.title }, time = now)
        } else {
            history.add(0, HistoryItem(title, url, now))
            while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        }
        store.saveHistory(history)
    }

    fun clearHistory() {
        history.clear()
        store.saveHistory(history)
    }

    fun removeHistory(item: HistoryItem) {
        history.remove(item)
        store.saveHistory(history)
    }

    fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

    fun toggleBookmark() {
        val t = active ?: return
        if (t.url.isEmpty()) return
        val existing = bookmarks.indexOfFirst { it.url == t.url }
        if (existing >= 0) {
            bookmarks.removeAt(existing)
        } else {
            bookmarks.add(Bookmark(t.title.ifEmpty { UrlUtils.display(t.url) }, t.url))
        }
        store.saveBookmarks(bookmarks)
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        store.saveBookmarks(bookmarks)
    }

    // ---- settings / data ----

    fun updateSettings(s: Settings) {
        val old = settings
        settings = s
        store.saveSettings(s)
        mgr?.applySettingsToAll(reloadForUa = old.desktopMode != s.desktopMode)
    }

    fun updateFilters() {
        adBlock.update(getApplication(), settings.filterUrl)
    }

    fun clearCache() {
        mgr?.clearCache()
    }

    fun clearCookies() {
        mgr?.clearCookies()
    }

    companion object {
        private const val MAX_HISTORY = 300
    }
}
