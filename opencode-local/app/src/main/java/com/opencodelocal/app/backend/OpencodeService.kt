package com.opencodelocal.app.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.opencodelocal.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 前景服務：保住本地 serve 不被 Android 12+ 幻影進程殺掉。
 * 被殺掉後圖形 UI 會顯示「後端已停止，一鍵重啟」按鈕。
 */
class OpencodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var backend: OpencodeBackend

    override fun onCreate() {
        super.onCreate()
        backend = OpencodeBackend(this)
        startForeground(1, buildNotification("Opencode 本地後端運行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("apiKey").orEmpty()
        val provider = intent?.getStringExtra("provider") ?: "openai"
        scope.launch {
            backend.startServer(apiKey, provider)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        backend.stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val channelId = "opencode_local"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "本地後端", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Opencode 本地版")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
