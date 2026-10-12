package com.opencodelocal.app.backend

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 負責：下載二進位 → 解壓 → chmod +x → 啟動 serve → 健康檢查。
 * 全部跑在手機本機，工作目錄就是 App 私有沙盒：
 *   filesDir/opencode-bin/ + filesDir/workspace/
 */
class OpencodeBackend(private val context: Context) {

    private val tag = "OpencodeBackend"
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var process: Process? = null

    fun workspaceDir(): File =
        File(context.filesDir, "workspace").apply { if (!exists()) mkdirs() }

    fun binDir(): File =
        File(context.filesDir, OpencodeConfig.BIN_DIR).apply { if (!exists()) mkdirs() }

    fun isInstalled(): Boolean {
        val wrapper = File(binDir(), OpencodeConfig.BIN_WRAPPER)
        val main = File(binDir(), OpencodeConfig.BIN_MAIN)
        return wrapper.exists() && main.exists() && wrapper.canExecute()
    }

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(OpencodeConfig.BASE_URL + OpencodeConfig.HEALTH_PATH)
                .get()
                .build()
            http.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            Log.d(tag, "health check fail: ${e.message}")
            false
        }
    }

    /**
     * 啟動本地 serve。呼叫前確保已安裝。
     * 環境變數只從 App 安全儲存讀取，不經任何第三方伺服器。
     */
    suspend fun startServer(apiKey: String, provider: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                stopServer()
                val wrapper = File(binDir(), OpencodeConfig.BIN_WRAPPER)
                if (!wrapper.exists()) return@withContext false

                wrapper.setExecutable(true)
                File(binDir(), OpencodeConfig.BIN_MAIN).setExecutable(true)

                val env = mapOf(
                    providerEnvKey(provider) to apiKey,
                    "HOME" to context.filesDir.absolutePath,
                    "XDG_CONFIG_HOME" to File(context.filesDir, ".config").apply { mkdirs() }.absolutePath,
                )

                val pb = ProcessBuilder(
                    wrapper.absolutePath,
                    "serve",
                    "--hostname", OpencodeConfig.HOST,
                    "--port", OpencodeConfig.PORT.toString()
                )
                    .directory(workspaceDir())
                    .redirectErrorStream(true)

                val environment = pb.environment()
                env.forEach { (k, v) -> environment[k] = v }

                process = pb.start()
                Log.i(tag, "serve started pid? local port ${OpencodeConfig.PORT}")

                // 等待健康檢查，最多等 15 秒
                repeat(30) {
                    kotlinx.coroutines.delay(500)
                    if (checkHealth()) return@withContext true
                }
                false
            } catch (e: Exception) {
                Log.e(tag, "startServer failed", e)
                false
            }
        }

    fun stopServer() {
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }

    private fun providerEnvKey(provider: String): String = when (provider) {
        "anthropic" -> "ANTHROPIC_API_KEY"
        "gemini" -> "GEMINI_API_KEY"
        else -> "OPENAI_API_KEY"
    }
}
