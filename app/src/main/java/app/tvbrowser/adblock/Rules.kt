package app.tvbrowser.adblock

object ResType {
    const val UNKNOWN = 0
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLE = 4
    const val XHR = 8
    const val SUBDOC = 16
    const val MEDIA = 32
    const val FONT = 64
    const val OTHER = 128
}

/** Request being matched; url is lowercase. */
class Ctx(val url: String, val host: String, val pageHost: String, val thirdParty: Boolean, val type: Int) {
    val tokens: List<String> by lazy { tokenize(url) }
}

fun tokenize(s: String): List<String> {
    val out = LinkedHashSet<String>()
    var i = 0
    while (i < s.length && out.size < 128) {
        if (s[i].isAsciiAlnum()) {
            val st = i
            while (i < s.length && s[i].isAsciiAlnum()) i++
            if (i - st >= 3) out.add(s.substring(st, i))
        } else {
            i++
        }
    }
    return out.toList()
}

fun Char.isAsciiAlnum(): Boolean = this in 'a'..'z' || this in '0'..'9'

fun domainMatch(host: String, domain: String): Boolean =
    host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')

class NetRule(
    val pattern: String,
    val anchorHost: Boolean,
    val anchorStart: Boolean,
    val anchorEnd: Boolean,
    val types: Int,
    val notTypes: Int,
    val party: Int, // 0 any, 1 third-party only, 2 first-party only
    val include: Array<String>?,
    val exclude: Array<String>?,
) {
    fun applies(c: Ctx): Boolean {
        if (party == 1 && !c.thirdParty) return false
        if (party == 2 && c.thirdParty) return false
        if (c.type != ResType.UNKNOWN) {
            if (types != 0 && (types and c.type) == 0) return false
            if ((notTypes and c.type) != 0) return false
        } else if (types != 0 && (types and (ResType.SCRIPT or ResType.XHR or ResType.OTHER or ResType.IMAGE)) == 0) {
            return false
        }
        if (include != null && include.none { domainMatch(c.pageHost, it) }) return false
        if (exclude != null && exclude.any { domainMatch(c.pageHost, it) }) return false
        return Matcher.match(this, c.url)
    }
}

object Matcher {
    fun match(r: NetRule, url: String): Boolean {
        val p = r.pattern
        if (r.anchorHost) {
            val sep = url.indexOf("://")
            if (sep < 0) return false
            val hs = sep + 3
            var he = hs
            while (he < url.length && url[he] != '/' && url[he] != '?' && url[he] != '#' && url[he] != ':') he++
            if (matchAt(p, 0, url, hs, r.anchorEnd)) return true
            var i = hs
            while (i < he) {
                if (url[i] == '.' && matchAt(p, 0, url, i + 1, r.anchorEnd)) return true
                i++
            }
            return false
        }
        if (r.anchorStart) return matchAt(p, 0, url, 0, r.anchorEnd)

        val lead = p.indexOfFirst { it == '*' || it == '^' }
        val prefix = if (lead < 0) p else p.substring(0, lead)
        if (prefix.isNotEmpty()) {
            var s = url.indexOf(prefix)
            while (s >= 0) {
                if (matchAt(p, 0, url, s, r.anchorEnd)) return true
                s = url.indexOf(prefix, s + 1)
            }
            return false
        }
        for (s in 0..url.length) if (matchAt(p, 0, url, s, r.anchorEnd)) return true
        return false
    }

    private fun isSeparator(c: Char): Boolean =
        !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

    private fun matchAt(p: String, pi0: Int, u: String, ui0: Int, anchorEnd: Boolean): Boolean {
        var pi = pi0
        var ui = ui0
        while (pi < p.length) {
            val c = p[pi]
            if (c == '*') {
                while (pi < p.length && p[pi] == '*') pi++
                if (pi == p.length) return true
                for (k in ui..u.length) if (matchAt(p, pi, u, k, anchorEnd)) return true
                return false
            } else if (c == '^') {
                if (ui == u.length) {
                    pi++
                } else if (isSeparator(u[ui])) {
                    ui++
                    pi++
                } else {
                    return false
                }
            } else {
                if (ui >= u.length || u[ui] != c) return false
                ui++
                pi++
            }
        }
        return !anchorEnd || ui == u.length
    }
}

/** Rules indexed by host, by rarest token, or kept in a short fallback list. */
class RuleIndex {
    private val byHost = HashMap<String, ArrayList<NetRule>>()
    private val byToken = HashMap<String, ArrayList<NetRule>>()
    private val loose = ArrayList<NetRule>()
    var size = 0
        private set

    fun add(r: NetRule) {
        size++
        val hk = hostKey(r)
        if (hk != null) {
            byHost.getOrPut(hk) { ArrayList() }.add(r)
            return
        }
        val tk = tokenKey(r)
        if (tk != null) {
            byToken.getOrPut(tk) { ArrayList() }.add(r)
            return
        }
        loose.add(r)
    }

    fun find(c: Ctx): NetRule? {
        var h = c.host
        while (h.isNotEmpty()) {
            val list = byHost[h]
            if (list != null) for (r in list) if (r.applies(c)) return r
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        for (t in c.tokens) {
            val list = byToken[t] ?: continue
            for (r in list) if (r.applies(c)) return r
        }
        for (r in loose) if (r.applies(c)) return r
        return null
    }

    private fun hostKey(r: NetRule): String? {
        if (!r.anchorHost) return null
        val p = r.pattern
        var end = 0
        while (end < p.length && (p[end].isAsciiAlnum() || p[end] == '.' || p[end] == '-')) end++
        if (end == 0 || end >= p.length) return null
        if (p[end] != '^' && p[end] != '/' && p[end] != ':' && p[end] != '?') return null
        val key = p.substring(0, end)
        return if (key.contains('.')) key else null
    }

    private fun tokenKey(r: NetRule): String? {
        val p = r.pattern
        var best: String? = null
        var i = 0
        while (i < p.length) {
            if (!p[i].isAsciiAlnum()) {
                i++
                continue
            }
            val s = i
            while (i < p.length && p[i].isAsciiAlnum()) i++
            val e = i
            val startOk = if (s > 0) p[s - 1] != '*' else (r.anchorStart || r.anchorHost)
            val endOk = if (e < p.length) p[e] != '*' else r.anchorEnd
            if (startOk && endOk && e - s >= 3 && (best == null || e - s > best.length)) {
                best = p.substring(s, e)
            }
        }
        return best
    }
}
