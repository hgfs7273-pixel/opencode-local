package com.opencodelocal.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencodelocal.app.backend.OpencodeApi
import kotlinx.coroutines.launch

class FilesViewModel(val sessionId: String) : ViewModel() {
    private val api = OpencodeApi()
    var diffs by mutableStateOf(listOf<OpencodeApi.FileDiffData>())
        private set
    var loading by mutableStateOf(false)
        private set
    var expanded by mutableStateOf<Int?>(null)
    var showBefore by mutableStateOf(false)

    fun load() {
        loading = true
        viewModelScope.launch {
            diffs = api.getDiff(sessionId)
            loading = false
        }
    }
}
