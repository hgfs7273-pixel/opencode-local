package com.opencodelocal.app.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 引擎（opencode 二進位）更新管理。
 *
 * 關鍵設計：引擎只住在 filesDir/opencode-bin，workspace 和設定住別處，
 * 所以「更新＝整個 bin 目錄刪掉重下 latest」，你的程式碼、任務、Key 都不會動。
 * 引擎本身不會自己更新（Android 版沒有更新通道），一定要靠這裡。
 */
class EngineManager(private val context: Context) {

    private val backend = OpencodeBackend(context)
    private val prefs = context.getSharedPreferences("opencode_local", Context.MODE_PRIVATE)

    /** 已安裝引擎的版本（跑 `opencode --version`），失敗回 null。 */
    suspend fun installedVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val bin = File(backend.binDir(), OpencodeConfig.BIN_WRAPPER)
            if (!bin.exists()) return@withContext null
            val p = ProcessBuilder(bin.absolutePath, "--version")
                .directory(backend.workspaceDir())
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val finished = p.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return@withContext null
            }
            prefs.edit().putString("engine_version", out.lineSequence().firstOrNull()).apply()
            out.lineSequence().firstOrNull()?.ifBlank { null }
        } catch (_: Exception) { null }
    }

    /** 上游最新引擎包版本（guysoft release tag，如 v1.0.2）。 */
    suspend fun latestEngineTag(): String? = withContext(Dispatchers.IO) {
        try {
            val raw = URL("https://api.github.com/repos/guysoft/opencode-termux/releases/latest")
                .openConnection().apply { connectTimeout = 10000; readTimeout = 10000 }
                .getInputStream().bufferedReader().readText()
            JSONObject(raw).optString("tag_name").ifBlank { null }
        } catch (_: Exception) { null }
    }

    fun cachedVersion(): String? = prefs.getString("engine_version", null)
    fun cachedLatestTag(): String? = prefs.getString("engine_latest_tag", null)

    /** 更新引擎：刪 bin 重下。進度回報給 UI 進度條。 */
    suspend fun updateEngine(onProgress: (OpencodeInstaller.Progress) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                backend.stopServer()
                backend.binDir().deleteRecursively()
                backend.binDir().mkdirs()
                OpencodeInstaller(context, backend).install(onProgress)
                val v = installedVersion()
                if (v != null) prefs.edit().putLong("engine_updated_at", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                onProgress(OpencodeInstaller.Progress.Failed(e.message ?: "更新失敗"))
            }
        }
    }

    fun saveLatestTag(tag: String) {
        prefs.edit().putString("engine_latest_tag", tag).apply()
    }

    fun lastUpdatedAt(): Long = prefs.getLong("engine_updated_at", 0L)
}
