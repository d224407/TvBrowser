package app.tvbrowser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class Store(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("tvb", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            searchEngine = sp.getInt("search", d.searchEngine),
            adBlock = sp.getBoolean("adblock", d.adBlock),
            javascript = sp.getBoolean("js", d.javascript),
            cursorMode = sp.getBoolean("cursor", d.cursorMode),
            desktopMode = sp.getBoolean("desktop", d.desktopMode),
            textZoom = sp.getInt("zoom", d.textZoom),
            thirdPartyCookies = sp.getBoolean("3pc", d.thirdPartyCookies),
            filterUrl = sp.getString("filterUrl", d.filterUrl) ?: d.filterUrl,
        )
    }

    fun saveSettings(s: Settings) {
        sp.edit()
            .putInt("search", s.searchEngine)
            .putBoolean("adblock", s.adBlock)
            .putBoolean("js", s.javascript)
            .putBoolean("cursor", s.cursorMode)
            .putBoolean("desktop", s.desktopMode)
            .putInt("zoom", s.textZoom)
            .putBoolean("3pc", s.thirdPartyCookies)
            .putString("filterUrl", s.filterUrl)
            .apply()
    }

    fun hasBookmarks(): Boolean = File(ctx.filesDir, "bookmarks.json").exists()

    fun loadBookmarks(): List<Bookmark> =
        read("bookmarks.json") { Bookmark(it.optString("t"), it.optString("u")) }

    fun saveBookmarks(list: List<Bookmark>) {
        val snap = list.toList()
        write("bookmarks.json", snap.map { JSONObject().put("t", it.title).put("u", it.url) })
    }

    fun loadHistory(): List<HistoryItem> =
        read("history.json") { HistoryItem(it.optString("t"), it.optString("u"), it.optLong("d")) }

    fun saveHistory(list: List<HistoryItem>) {
        val snap = list.toList()
        write("history.json", snap.map { JSONObject().put("t", it.title).put("u", it.url).put("d", it.time) })
    }

    fun loadTabs(): Pair<List<TabSnap>, Long> {
        val tabs = read("tabs.json") { TabSnap(it.optLong("i"), it.optString("u"), it.optString("t")) }
        return tabs to sp.getLong("activeTab", -1L)
    }

    fun saveTabs(tabs: List<TabSnap>, active: Long) {
        sp.edit().putLong("activeTab", active).apply()
        write("tabs.json", tabs.map { JSONObject().put("i", it.id).put("u", it.url).put("t", it.title) })
    }

    private fun <T> read(name: String, map: (JSONObject) -> T): List<T> {
        return try {
            val f = File(ctx.filesDir, name)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { map(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(name: String, items: List<JSONObject>) {
        io.execute {
            try {
                val tmp = File(ctx.filesDir, "$name.tmp")
                tmp.writeText(JSONArray(items).toString())
                tmp.renameTo(File(ctx.filesDir, name))
            } catch (_: Exception) {
            }
        }
    }
}
