package com.opencodelocal.app.backend

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 訂閱 GET /event（標準 SSE：空白行分段、data: 行為 JSON）。
 * 事件的 type 就在 JSON 裡（server.connected、message.part.updated、
 * permission.updated …），不斷線重連，直到 stop()。
 */
class EventBus {

    private val tag = "EventBus"
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 長連線
        .build()

    private var scope: CoroutineScope? = null
    @Volatile private var running = false

    fun start(onEvent: (type: String, props: JSONObject) -> Unit) {
        if (running) return
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (isActive && running) {
                try {
                    listenOnce(onEvent)
                } catch (e: Exception) {
                    Log.w(tag, "event stream broken: ${e.message}")
                }
                if (running) delay(3000) // 斷線 3 秒重連
            }
        }
    }

    private fun listenOnce(onEvent: (String, JSONObject) -> Unit) {
        val req = Request.Builder().url(OpencodeConfig.BASE_URL + "/event").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("event http ${resp.code}")
            val src = resp.body?.source() ?: return
            val dataLines = mutableListOf<String>()
            while (running) {
                val line = try {
                    src.readUtf8Line() ?: break
                } catch (_: Exception) { break }
                if (line.isBlank()) {
                    if (dataLines.isNotEmpty()) {
                        val raw = dataLines.joinToString("\n")
                        dataLines.clear()
                        try {
                            val obj = JSONObject(raw)
                            val type = obj.optString("type")
                            if (type.isNotBlank()) {
                                onEvent(type, obj.optJSONObject("properties") ?: JSONObject())
                            }
                        } catch (_: Exception) { /* 心跳或非 JSON，略過 */ }
                    }
                } else if (line.startsWith("data:")) {
                    dataLines.add(line.removePrefix("data:").trimStart())
                }
                // event:/id:/retry: 行不需要，type 在 JSON 內
            }
        }
    }

    fun stop() {
        running = false
        scope?.cancel()
        scope = null
    }
}
