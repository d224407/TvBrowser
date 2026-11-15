package app.tvbrowser.adblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AdBlockEngine {
    private class State(
        val block: RuleIndex,
        val allow: RuleIndex,
        val generic: List<String>,
        val byDomain: Map<String, List<String>>,
        val exceptions: Map<String, Set<String>>,
    )

    @Volatile
    private var state = State(RuleIndex(), RuleIndex(), emptyList(), emptyMap(), emptyMap())
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var ruleCount by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        io.execute { rebuild(ctx) }
    }

    fun update(ctx: Context, url: String) {
        if (busy) return
        busy = true
        io.execute {
            try {
                download(ctx, url)
            } catch (_: Exception) {
            }
            rebuild(ctx)
        }
    }

    private fun download(ctx: Context, url: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) return
            val tmp = File(ctx.filesDir, "filters.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_LIST_BYTES) {
                            tmp.delete()
                            return
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (tmp.length() > 1024) tmp.renameTo(File(ctx.filesDir, "filters.txt")) else tmp.delete()
        } finally {
            conn.disconnect()
        }
    }

    private fun rebuild(ctx: Context) {
        val b = Builder()
        try {
            ctx.assets.open("filters.txt").bufferedReader().useLines { seq -> seq.forEach { b.parse(it) } }
        } catch (_: Exception) {
        }
        val f = File(ctx.filesDir, "filters.txt")
        if (f.exists()) {
            try {
                f.bufferedReader().useLines { seq -> seq.forEach { b.parse(it) } }
            } catch (_: Exception) {
            }
        }
        val st = b.build()
        state = st
        val n = st.block.size + st.allow.size
        main.post {
            ruleCount = n
            busy = false
        }
    }

    fun shouldBlock(url: String, pageHost: String, type: Int): Boolean {
        val st = state
        if (st.block.size == 0) return false
        val lower = url.lowercase()
        val host = hostOf(lower)
        if (host.isEmpty()) return false
        val c = Ctx(lower, host, pageHost, isThirdParty(host, pageHost), type)
        if (st.block.find(c) == null) return false
        return st.allow.find(c) == null
    }

    fun cosmeticCss(host: String): String {
        val st = state
        val sels = LinkedHashSet<String>(st.generic)
        var h = host
        while (h.isNotEmpty()) {
            st.byDomain[h]?.let { sels.addAll(it) }
            st.exceptions[h]?.let { sels.removeAll(it) }
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        st.exceptions[""]?.let { sels.removeAll(it) }
        if (sels.isEmpty()) return ""
        val sb = StringBuilder()
        for (s in sels) sb.append(s).append("{display:none!important}")
        return sb.toString()
    }

    private class Builder {
        val block = RuleIndex()
        val allow = RuleIndex()
        val generic = LinkedHashSet<String>()
        val byDomain = HashMap<String, ArrayList<String>>()
        val exceptions = HashMap<String, HashSet<String>>()

        fun parse(raw: String) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return
            val ci = line.indexOf("##")
            val ce = line.indexOf("#@#")
            when {
                ci >= 0 && (ce < 0 || ci < ce) -> cosmetic(line.substring(0, ci), line.substring(ci + 2), false)
                ce >= 0 -> cosmetic(line.substring(0, ce), line.substring(ce + 3), true)
                line.contains("#?#") || line.contains("#$#") || line.contains("#%#") -> Unit
                else -> network(line)
            }
        }

        private fun cosmetic(domains: String, selector: String, exception: Boolean) {
            val sel = selector.trim()
            if (sel.isEmpty() || sel.length > 200) return
            if (sel.contains(":-abp-") || sel.contains(":has-text") || sel.contains(":xpath") ||
                sel.contains(":matches-") || sel.contains(":contains(") || sel.contains(":upward") ||
                sel.contains("+js(") || sel.contains("{") || sel.contains("}")
            ) return
            val inc = ArrayList<String>()
            var hasExclude = false
            if (domains.isNotEmpty()) {
                for (d in domains.split(',')) {
                    val t = d.trim().lowercase()
                    if (t.isEmpty()) continue
                    if (t.startsWith("~")) hasExclude = true else inc.add(t)
                }
            }
            if (inc.isEmpty()) {
                if (hasExclude) return
                if (exception) exceptions.getOrPut("") { HashSet() }.add(sel)
                else if (generic.size < MAX_GENERIC) generic.add(sel)
                return
            }
            for (d in inc) {
                if (exception) exceptions.getOrPut(d) { HashSet() }.add(sel)
                else byDomain.getOrPut(d) { ArrayList() }.add(sel)
            }
        }

        private fun network(line0: String) {
            var line = line0
            val exception = line.startsWith("@@")
            if (exception) line = line.substring(2)

            var opts = ""
            val di = line.lastIndexOf('$')
            if (di >= 0 && !line.substring(di).contains('/')) {
                opts = line.substring(di + 1)
                line = line.substring(0, di)
            }
            if (line.length > 2 && line.startsWith("/") && line.endsWith("/")) return // regex rule

            var types = 0
            var notTypes = 0
            var party = 0
            var include: ArrayList<String>? = null
            var exclude: ArrayList<String>? = null
            if (opts.isNotEmpty()) {
                for (o in opts.split(',')) {
                    val opt = o.trim().lowercase()
                    val neg = opt.startsWith("~")
                    val name = opt.removePrefix("~")
                    when {
                        name == "third-party" || name == "3p" -> party = if (neg) 2 else 1
                        name == "important" || name == "match-case" -> Unit
                        name.startsWith("domain=") -> {
                            for (d in name.removePrefix("domain=").split('|')) {
                                if (d.isEmpty()) continue
                                if (d.startsWith("~")) {
                                    if (exclude == null) exclude = ArrayList()
                                    exclude.add(d.substring(1))
                                } else {
                                    if (include == null) include = ArrayList()
                                    include.add(d)
                                }
                            }
                        }
                        else -> {
                            val t = typeOf(name)
                            if (t == 0) return // unsupported option: skip rule
                            if (neg) notTypes = notTypes or t else types = types or t
                        }
                    }
                }
            }

            var anchorHost = false
            var anchorStart = false
            var anchorEnd = false
            if (line.startsWith("||")) {
                anchorHost = true
                line = line.substring(2)
            } else if (line.startsWith("|")) {
                anchorStart = true
                line = line.substring(1)
            }
            if (line.endsWith("|")) {
                anchorEnd = true
                line = line.substring(0, line.length - 1)
            }
            line = line.lowercase()
            if (line.isEmpty() || line == "*") return

            val rule = NetRule(
                line, anchorHost, anchorStart, anchorEnd, types, notTypes, party,
                include?.toTypedArray(), exclude?.toTypedArray(),
            )
            if (exception) allow.add(rule) else block.add(rule)
        }

        private fun typeOf(name: String): Int = when (name) {
            "script" -> ResType.SCRIPT
            "image" -> ResType.IMAGE
            "stylesheet" -> ResType.STYLE
            "xmlhttprequest" -> ResType.XHR
            "subdocument" -> ResType.SUBDOC
            "media" -> ResType.MEDIA
            "font" -> ResType.FONT
            "object", "other", "ping", "websocket" -> ResType.OTHER
            else -> 0
        }

        fun build() = State(block, allow, generic.toList(), byDomain, exceptions)
    }

    companion object {
        private const val MAX_LIST_BYTES = 20L * 1024 * 1024
        private const val MAX_GENERIC = 3000

        fun hostOf(lowerUrl: String): String {
            val s = lowerUrl.indexOf("://")
            if (s < 0) return ""
            var i = s + 3
            val st = i
            while (i < lowerUrl.length) {
                val c = lowerUrl[i]
                if (c == '/' || c == '?' || c == '#' || c == ':') break
                i++
            }
            val h = lowerUrl.substring(st, i)
            return h.substringAfterLast('@')
        }

        private val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac")

        fun baseDomain(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val n = if (parts.last().length == 2 && parts[parts.size - 2] in secondLevel) 3 else 2
            return parts.takeLast(n).joinToString(".")
        }

        fun isThirdParty(host: String, pageHost: String): Boolean {
            if (pageHost.isEmpty()) return false
            return baseDomain(host) != baseDomain(pageHost)
        }

        fun guessType(url: String, accept: String?): Int {
            val path = url.substringBefore('#').substringBefore('?').lowercase()
            val ext = path.substringAfterLast('.', "")
            return when (ext) {
                "js", "mjs" -> ResType.SCRIPT
                "css" -> ResType.STYLE
                "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> ResType.IMAGE
                "mp4", "webm", "m3u8", "mp3", "ogg", "ts", "m4s" -> ResType.MEDIA
                "woff", "woff2", "ttf", "otf" -> ResType.FONT
                "html", "htm" -> ResType.SUBDOC
                "json" -> ResType.XHR
                else -> when {
                    accept == null -> ResType.UNKNOWN
                    accept.startsWith("image/") -> ResType.IMAGE
                    accept.contains("text/css") -> ResType.STYLE
                    accept.contains("text/html") -> ResType.SUBDOC
                    else -> ResType.UNKNOWN
                }
            }
        }
    }
}
