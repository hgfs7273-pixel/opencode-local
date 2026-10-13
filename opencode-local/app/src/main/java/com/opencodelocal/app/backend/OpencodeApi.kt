package com.opencodelocal.app.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 對接 opencode V1 合約（/session、/event、/global/health …），
 * 依據 @opencode-ai/sdk 1.19.0 型別實作。全部打 127.0.0.1，不出手機。
 */
class OpencodeApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class ApiSession(val id: String, val title: String, val updated: Long)
    data class ChatText(val id: String, val messageId: String, val text: String, val isUser: Boolean)
    data class ChatTool(val id: String, val messageId: String, val tool: String, val status: String)
    data class ChatMessage(
        val id: String,
        val role: String,
        val texts: List<ChatText>,
        val tools: List<ChatTool>,
        val error: String?,
        val completed: Boolean
    )
    data class PermissionReq(
        val id: String,
        val sessionId: String,
        val type: String,
        val pattern: String,
        val title: String
    )
    data class FileDiffData(
        val file: String,
        val before: String,
        val after: String,
        val additions: Int,
        val deletions: Int
    )
    data class ProviderModel(
        val providerId: String,
        val providerName: String,
        val modelId: String,
        val modelName: String
    )

    private fun get(path: String): String? {
        val req = Request.Builder().url(OpencodeConfig.BASE_URL + path).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun post(path: String, json: String): String? {
        val req = Request.Builder()
            .url(OpencodeConfig.BASE_URL + path)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 204) return null
            return resp.body?.string() ?: ""
        }
    }

    private fun delete(path: String): Boolean {
        val req = Request.Builder().url(OpencodeConfig.BASE_URL + path).delete().build()
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    // ---------- 會話 ----------

    suspend fun listSessions(): List<ApiSession> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray(get("/session") ?: return@withContext emptyList())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                ApiSession(
                    id = id,
                    title = o.optString("title").ifBlank { "未命名任務" },
                    updated = o.optJSONObject("time")?.optLong("updated", 0L) ?: 0L
                )
            }.sortedByDescending { it.updated }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun createSession(title: String): ApiSession? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("title", title).toString()
            val o = JSONObject(post("/session", body) ?: return@withContext null)
            val id = o.optString("id").ifBlank { return@withContext null }
            ApiSession(id, o.optString("title").ifBlank { title }, System.currentTimeMillis())
        } catch (_: Exception) { null }
    }

    suspend fun deleteSession(id: String): Boolean = withContext(Dispatchers.IO) {
        try { delete("/session/$id") } catch (_: Exception) { false }
    }

    suspend fun abortSession(id: String): Boolean = withContext(Dispatchers.IO) {
        try { post("/session/$id/abort", "{}") != null } catch (_: Exception) { false }
    }

    // ---------- 訊息 ----------

    private fun parsePart(p: JSONObject, isUser: Boolean): Pair<ChatText?, ChatTool?> {
        val type = p.optString("type")
        val pid = p.optString("id")
        val mid = p.optString("messageID")
        return when (type) {
            "text" -> Pair(ChatText(pid, mid, p.optString("text"), isUser), null)
            "tool" -> Pair(
                null,
                ChatTool(pid, mid, p.optString("tool").ifBlank { "工具" }, p.optJSONObject("state")?.optString("status", "").orEmpty())
            )
            else -> Pair(null, null)
        }
    }

    suspend fun listMessages(sessionId: String, limit: Int = 100): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray(get("/session/$sessionId/message?limit=$limit") ?: return@withContext emptyList())
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    val info = item.optJSONObject("info") ?: return@mapNotNull null
                    val role = info.optString("role", "assistant")
                    val isUser = role == "user"
                    val texts = mutableListOf<ChatText>()
                    val tools = mutableListOf<ChatTool>()
                    val parts = item.optJSONArray("parts") ?: JSONArray()
                    (0 until parts.length()).forEach { j ->
                        val (t, tl) = parsePart(parts.optJSONObject(j) ?: return@forEach, isUser)
                        if (t != null) texts.add(t)
                        if (tl != null) tools.add(tl)
                    }
                    val errObj = info.optJSONObject("error")
                    ChatMessage(
                        id = info.optString("id"),
                        role = role,
                        texts = texts,
                        tools = tools,
                        error = if (errObj != null) errObj.optString("message").ifBlank { "AI 回覆出錯" } else null,
                        completed = info.optJSONObject("time")?.has("completed") == true || isUser
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

    /**
     * 非同步送訊息（204），回覆靠 /event 串流回來。
     */
    suspend fun sendAsync(
        sessionId: String,
        text: String,
        providerId: String?,
        modelId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val part = JSONObject().put("type", "text").put("text", text)
            val body = JSONObject().put("parts", JSONArray().put(part))
            if (!providerId.isNullOrBlank() && !modelId.isNullOrBlank()) {
                body.put("model", JSONObject().put("providerID", providerId).put("modelID", modelId))
            }
            post("/session/$sessionId/prompt_async", body.toString()) != null
        } catch (_: Exception) { false }
    }

    // ---------- 權限 ----------

    suspend fun respondPermission(sessionId: String, permissionId: String, response: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // response: once（批准一次）| always（總是批准）| reject（拒絕）
                val body = JSONObject().put("response", response).toString()
                post("/session/$sessionId/permissions/$permissionId", body) != null
            } catch (_: Exception) { false }
        }

    // ---------- Diff / 檔案 ----------

    suspend fun getDiff(sessionId: String): List<FileDiffData> = withContext(Dispatchers.IO) {
        try {
            val raw = get("/session/$sessionId/diff") ?: return@withContext emptyList()
            val arr = try {
                JSONArray(raw)
            } catch (_: Exception) {
                JSONObject(raw).optJSONArray("diffs") ?: return@withContext emptyList()
            }
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FileDiffData(
                    file = o.optString("file"),
                    before = o.optString("before"),
                    after = o.optString("after"),
                    additions = o.optInt("additions"),
                    deletions = o.optInt("deletions")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getFileContent(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(path, "UTF-8")
            val o = JSONObject(get("/file/content?path=$enc") ?: return@withContext null)
            if (o.optString("type") == "binary") null else o.optString("content")
        } catch (_: Exception) { null }
    }

    // ---------- 模型 ----------

    /** 最佳努力：讀不到就回空，UI 改用伺服器預設模型。 */
    suspend fun listModels(): Pair<List<ProviderModel>, Map<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject(get("/config/providers") ?: return@withContext Pair(emptyList(), emptyMap()))
                val out = mutableListOf<ProviderModel>()
                val providers = root.optJSONArray("providers") ?: JSONArray()
                (0 until providers.length()).forEach { i ->
                    val p = providers.optJSONObject(i) ?: return@forEach
                    val pid = p.optString("id")
                    val pname = p.optString("name").ifBlank { pid }
                    val models = p.optJSONObject("models")
                    if (models != null) {
                        val keys = models.keys()
                        while (keys.hasNext()) {
                            val mid = keys.next()
                            val m = models.optJSONObject(mid)
                            out.add(ProviderModel(pid, pname, mid, m?.optString("name")?.ifBlank { mid } ?: mid))
                        }
                    } else {
                        val arr = p.optJSONArray("models")
                        if (arr != null) {
                            (0 until arr.length()).forEach { j ->
                                val m = arr.optJSONObject(j)
                                if (m != null) {
                                    val mid = m.optString("id")
                                    out.add(ProviderModel(pid, pname, mid, m.optString("name").ifBlank { mid }))
                                } else {
                                    val s = arr.optString(j)
                                    if (s.isNotBlank()) out.add(ProviderModel(pid, pname, s, s))
                                }
                            }
                        }
                    }
                }
                val defObj = root.optJSONObject("default") ?: JSONObject()
                val defs = mutableMapOf<String, String>()
                val dk = defObj.keys()
                while (dk.hasNext()) {
                    val k = dk.next()
                    defs[k] = defObj.optString(k)
                }
                Pair(out, defs)
            } catch (_: Exception) { Pair(emptyList(), emptyMap()) }
        }
}
