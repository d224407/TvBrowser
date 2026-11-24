package app.tvbrowser.data

import android.os.Build
import app.tvbrowser.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    class Release(val version: String, val apkUrl: String?)

    fun fetch(repo: String): Release? {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val version = json.optString("tag_name").removePrefix("v")
            if (version.isEmpty()) return null
            val assets = json.optJSONArray("assets")
            val urls = HashMap<String, String>()
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    urls[a.optString("name")] = a.optString("browser_download_url")
                }
            }
            val apk = candidates().firstNotNullOfOrNull { urls[it] }
            return Release(version, apk)
        } finally {
            conn.disconnect()
        }
    }

    /** Asset names for this build, best match first. */
    private fun candidates(): List<String> =
        if (BuildConfig.FLAVOR == "gecko") {
            Build.SUPPORTED_ABIS.map { "TvBrowser-gecko-$it.apk" }
        } else {
            listOf("TvBrowser-generic.apk")
        }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
