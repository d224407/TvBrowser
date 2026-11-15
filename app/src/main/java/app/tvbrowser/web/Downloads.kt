package app.tvbrowser.web

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val bytes: Long,
    val total: Long,
    val mime: String,
)

object Downloads {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    private fun ids(ctx: Context): List<Long> =
        prefs(ctx).getString("ids", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun saveIds(ctx: Context, ids: List<Long>) {
        prefs(ctx).edit().putString("ids", ids.joinToString(",")).apply()
    }

    private fun dm(ctx: Context) = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(ctx: Context, url: String, ua: String?, disposition: String?, mime: String?): Boolean {
        if (!url.startsWith("http")) return false
        return try {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
            }
            val id = dm(ctx).enqueue(req)
            saveIds(ctx, listOf(id) + ids(ctx))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun query(ctx: Context): List<DownloadItem> {
        val list = ids(ctx)
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<DownloadItem>()
        val c = dm(ctx).query(DownloadManager.Query().setFilterById(*list.toLongArray())) ?: return out
        c.use {
            while (it.moveToNext()) {
                out.add(
                    DownloadItem(
                        id = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                        title = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty(),
                        status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        mime = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)).orEmpty(),
                    ),
                )
            }
        }
        return out.sortedByDescending { list.size - list.indexOf(it.id) }
    }

    fun open(ctx: Context, item: DownloadItem) {
        try {
            val uri = dm(ctx).getUriForDownloadedFile(item.id) ?: return
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, item.mime.ifEmpty { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }

    fun remove(ctx: Context, id: Long) {
        try {
            dm(ctx).remove(id)
        } catch (_: Exception) {
        }
        saveIds(ctx, ids(ctx).filter { it != id })
    }
}
