package com.opencodelocal.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencodelocal.app.backend.OpencodeApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtTime(millis: Long): String {
    if (millis <= 0) return ""
    return SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(millis))
}

class HomeViewModel : ViewModel() {
    private val api = OpencodeApi()
    var sessions by mutableStateOf(listOf<OpencodeApi.ApiSession>())
        private set
    var loading by mutableStateOf(false)
        private set

    fun load() {
        loading = true
        viewModelScope.launch {
            sessions = api.listSessions()
            loading = false
        }
    }

    fun create(title: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val s = api.createSession(title) ?: return@launch
            sessions = api.listSessions()
            onCreated(s.id)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            if (api.deleteSession(id)) sessions = sessions.filter { it.id != id }
        }
    }
}
