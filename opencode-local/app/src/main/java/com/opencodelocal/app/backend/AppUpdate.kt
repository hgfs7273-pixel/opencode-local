package com.opencodelocal.app.backend

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * App 本體更新檢查：對你自己倉庫的 releases/latest，
 * 有新版就給下載連結（瀏覽器開 Release 頁覆蓋安裝，沙盒資料會保留）。
 */
object AppUpdate {

    data class Result(
        val current: String,
        val latest: String?,
        val url: String?,
        val available: Boolean
    )

    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val current = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }
        } catch (_: PackageManager.NameNotFoundException) { "?" }
        try {
            val raw = URL("https://api.github.com/repos/hgfs7273-pixel/opencode-local/releases/latest")
                .openConnection().apply { connectTimeout = 10000; readTimeout = 10000 }
                .getInputStream().bufferedReader().readText()
            val o = JSONObject(raw)
            val tag = o.optString("tag_name")
            val page = o.optString("html_url").ifBlank { null }
            val norm = { s: String -> s.trim().removePrefix("v") }
            Result(current, tag.ifBlank { null }, page, tag.isNotBlank() && norm(tag) != norm(current))
        } catch (_: Exception) {
            Result(current, null, null, false)
        }
    }
}
