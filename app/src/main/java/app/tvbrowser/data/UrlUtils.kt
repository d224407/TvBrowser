package app.tvbrowser.data

import android.net.Uri

object UrlUtils {
    val engines = listOf(
        "Google" to "https://www.google.com/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Baidu" to "https://www.baidu.com/s?wd=",
    )

    private val rawSchemes = listOf("about:", "data:", "file:", "view-source:")
    private val localHost = Regex("^(localhost|(\\d{1,3}\\.){3}\\d{1,3})(:\\d+)?([/?#].*)?$")

    fun resolve(input: String, engine: Int): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (s.contains("://") || rawSchemes.any { s.startsWith(it) }) return s
        if (localHost.matches(s)) return "http://$s"
        val hostLike = !s.contains(' ') && s.contains('.')
        if (hostLike) return "https://$s"
        val base = engines[engine.coerceIn(0, engines.lastIndex)].second
        return base + Uri.encode(s)
    }

    fun host(url: String): String = try {
        Uri.parse(url).host.orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun display(url: String): String {
        val h = host(url)
        return if (h.isEmpty()) url else h.removePrefix("www.")
    }
}
