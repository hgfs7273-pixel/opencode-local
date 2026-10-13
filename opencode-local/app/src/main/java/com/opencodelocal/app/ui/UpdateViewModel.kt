package com.opencodelocal.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencodelocal.app.backend.AppUpdate
import com.opencodelocal.app.backend.EngineManager
import com.opencodelocal.app.backend.OpencodeInstaller
import kotlinx.coroutines.launch

/** 主頁右上選單：引擎更新＋App 更新。 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val engines = EngineManager(app)

    var engineVersion by mutableStateOf<String?>(null)
        private set
    var engineLatest by mutableStateOf<String?>(null)
        private set
    var updating by mutableStateOf(false)
        private set
    var updateProgress by mutableStateOf(0)
        private set
    var updateStatus by mutableStateOf("")
        private set
    var appResult by mutableStateOf<AppUpdate.Result?>(null)
        private set
    var checkingApp by mutableStateOf(false)
        private set

    fun loadVersions() {
        viewModelScope.launch {
            engineVersion = engines.cachedVersion()
            engineLatest = engines.cachedLatestTag()
            engineVersion = engines.installedVersion() ?: engineVersion
            val tag = engines.latestEngineTag()
            if (tag != null) {
                engines.saveLatestTag(tag)
                engineLatest = tag
            }
        }
    }

    fun updateEngine() {
        if (updating) return
        updating = true
        updateProgress = 0
        viewModelScope.launch {
            engines.updateEngine {
                when (it) {
                    is OpencodeInstaller.Progress.Downloading -> {
                        updateProgress = it.percent
                        updateStatus = "下載新引擎 ${it.percent}%"
                    }
                    is OpencodeInstaller.Progress.Extracting -> updateStatus = "解壓安裝中…"
                    is OpencodeInstaller.Progress.Done -> updateStatus = "安裝完成"
                    is OpencodeInstaller.Progress.Failed -> {
                        updateStatus = "失敗：${it.reason}"
                        updating = false
                        return@updateEngine
                    }
                }
            }
            updating = false
            updateStatus = "引擎已更新，回到設定頁重啟後端即可生效"
            engineVersion = engines.installedVersion()
        }
    }

    fun checkApp() {
        checkingApp = true
        viewModelScope.launch {
            appResult = AppUpdate.check(getApplication())
            checkingApp = false
        }
    }
}
