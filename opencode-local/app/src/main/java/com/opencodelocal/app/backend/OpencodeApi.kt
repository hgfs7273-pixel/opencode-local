package com.opencodelocal.app.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 圖形 UI 專用的 API 層：會話列表、送訊息、批准/拒絕工具呼叫。
 * 全部打 127.0.0.1，不出手機。
 */
class OpencodeApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Session(val id: String, val title: String)
    data class ToolCall(val id: String, val name: String, val detail: String)

    suspend fun listSessions(): List<Session> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${OpencodeConfig.BASE_URL}/session")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = json.parseToJsonElement(resp.body!!.string()).jsonArray
                arr.map {
                    val o = it.jsonObject
                    Session(
                        id = o["id"]?.jsonPrimitive?.content ?: "",
                        title = o["title"]?.jsonPrimitive?.content ?: "未命名任務"
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun sendMessage(sessionId: String, text: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = "{\"sessionID\":\"$sessionId\",\"parts\":[{\"type\":\"text\",\"text\":\"${
                    text.replace("\"", "\\\"")
                }\"}]}"
                val req = Request.Builder()
                    .url("${OpencodeConfig.BASE_URL}/session/$sessionId/message")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { it.isSuccessful }
            } catch (_: Exception) { false }
        }

    suspend fun approveToolCall(sessionId: String, toolId: String, approve: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val action = if (approve) "accept" else "reject"
                val body = "{\"action\":\"$action\"}"
                val req = Request.Builder()
                    .url("${OpencodeConfig.BASE_URL}/session/$sessionId/permissions/$toolId")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { it.isSuccessful }
            } catch (_: Exception) { false }
        }
}
