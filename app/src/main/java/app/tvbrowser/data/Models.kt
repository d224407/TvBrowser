package app.tvbrowser.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Panel { NONE, MENU, TABS, BOOKMARKS, HISTORY, DOWNLOADS, SETTINGS, ADDRESS, CONTEXT }

class TabState(val id: Long, url: String = "", title: String = "") {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)

    // Bumped to force the WebView host to rebind (e.g. after renderer death)
    var generation by mutableIntStateOf(0)
}

data class TabSnap(val id: Long, val url: String, val title: String)
data class Bookmark(val title: String, val url: String)
data class HistoryItem(val title: String, val url: String, val time: Long)

data class Settings(
    val searchEngine: Int = 0,
    val adBlock: Boolean = true,
    val javascript: Boolean = true,
    val cursorMode: Boolean = true,
    val desktopMode: Boolean = false,
    val textZoom: Int = 100,
    val thirdPartyCookies: Boolean = true,
    val historySuggestions: Boolean = true,
    val filterUrl: String = "https://easylist.to/easylist/easylist.txt",
)
