package com.opencodelocal.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencodelocal.app.backend.EventBus
import com.opencodelocal.app.backend.OpencodeApi
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChatViewModel(val sessionId: String) : ViewModel() {

    private val api = OpencodeApi()
    private val bus = EventBus()

    var messages by mutableStateOf(listOf<OpencodeApi.ChatMessage>())
        private set
    /** 串流中的文字：messageId -> 已累積文字 */
    var streaming by mutableStateOf(mapOf<String, String>())
        private set
    /** 串流中的工具狀態：partId -> tool */
    var liveTools by mutableStateOf(mapOf<String, OpencodeApi.ChatTool>())
        private set
    var permissions by mutableStateOf(listOf<OpencodeApi.PermissionReq>())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("閒置")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var diffCount by mutableStateOf(0)
        private set
    var models by mutableStateOf(listOf<OpencodeApi.ProviderModel>())
        private set
    var selectedModel by mutableStateOf<OpencodeApi.ProviderModel?>(null)

    init {
        reload()
        refreshDiff()
        viewModelScope.launch {
            val (m, _) = api.listModels()
            models = m
        }
        bus.start(::onEvent)
    }

    override fun onCleared() {
        bus.stop()
        super.onCleared()
    }

    fun reload() {
        viewModelScope.launch {
            messages = api.listMessages(sessionId)
        }
    }

    fun refreshDiff() {
        viewModelScope.launch { diffCount = api.getDiff(sessionId).size }
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        busy = true
        status = "回答中…"
        error = null
        viewModelScope.launch {
            val ok = api.sendAsync(sessionId, text, selectedModel?.providerId, selectedModel?.modelId)
            if (!ok) {
                busy = false
                status = "傳送失敗"
                error = "訊息送不出去，請檢查後端是否還活著（回設定頁重啟）。"
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            api.abortSession(sessionId)
            busy = false
            status = "已停止"
        }
    }

    fun respond(p: OpencodeApi.PermissionReq, response: String) {
        viewModelScope.launch {
            api.respondPermission(sessionId, p.id, response)
            // 卡片等 permission.replied 事件回來再移除；先樂觀移除避免重複按
            permissions = permissions.filter { it.id != p.id }
        }
    }

    private fun onEvent(type: String, props: JSONObject) {
        when (type) {
            "message.part.updated" -> {
                val part = props.optJSONObject("part") ?: return
                if (part.optString("sessionID") != sessionId) return
                val mid = part.optString("messageID")
                when (part.optString("type")) {
                    "text" -> {
                        val delta = if (props.has("delta") && !props.isNull("delta")) {
                            props.optString("delta")
                        } else {
                            null
                        }
                        val cur = streaming[mid].orEmpty()
                        streaming = streaming + (mid to if (delta != null) cur + delta else part.optString("text"))
                    }
                    "tool" -> {
                        val pid = part.optString("id")
                        val tool = OpencodeApi.ChatTool(
                            pid, mid,
                            part.optString("tool").ifBlank { "工具" },
                            part.optJSONObject("state")?.optString("status").orEmpty()
                        )
                        liveTools = liveTools + (pid to tool)
                    }
                }
            }
            "message.updated" -> {
                val info = props.optJSONObject("info") ?: return
                if (info.optString("sessionID") != sessionId) return
                streaming = streaming - info.optString("id")
                reload()
            }
            "permission.updated" -> {
                if (props.optString("sessionID") != sessionId) return
                val id = props.optString("id")
                if (id.isBlank() || permissions.any { it.id == id }) return
                val pattern = when (val p = props.opt("pattern")) {
                    is org.json.JSONArray -> (0 until p.length()).mapNotNull { p.optString(it) }.joinToString("\n")
                    is String -> p
                    else -> ""
                }
                permissions = permissions + OpencodeApi.PermissionReq(
                    id = id,
                    sessionId = sessionId,
                    type = props.optString("type").ifBlank { "操作" },
                    pattern = pattern,
                    title = props.optString("title").ifBlank { "AI 請求操作授權" }
                )
            }
            "permission.replied" -> {
                if (props.optString("sessionID") != sessionId) return
                permissions = permissions.filter { it.id != props.optString("permissionID") }
            }
            "session.idle" -> {
                if (props.optString("sessionID") != sessionId) return
                busy = false
                status = "已完成"
                reload()
                refreshDiff()
            }
            "session.status" -> {
                if (props.optString("sessionID") != sessionId) return
                val st = props.optJSONObject("status")?.optString("type")
                if (st == "idle") {
                    busy = false
                    status = "已完成"
                    reload()
                    refreshDiff()
                } else {
                    busy = true
                    status = "回答中…"
                }
            }
            "session.error" -> {
                val sid = props.optString("sessionID")
                if (sid.isNotBlank() && sid != sessionId) return
                busy = false
                status = "出錯"
                error = "後端回報錯誤，請重試或換模型。"
            }
            "session.diff" -> {
                if (props.optString("sessionID") != sessionId) return
                diffCount = props.optJSONArray("diff")?.length() ?: diffCount
            }
        }
    }
}
