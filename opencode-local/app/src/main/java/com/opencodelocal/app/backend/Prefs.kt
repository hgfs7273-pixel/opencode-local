package com.opencodelocal.app.backend

import android.content.Context

/**
 * 記住模型商＋API Key（App 私有空間，其他 App 讀不到）。
 * 這樣每天打開不用重貼，後端自動啟動；更新引擎/App 也不用重設。
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("opencode_local", Context.MODE_PRIVATE)

    fun saveAuth(provider: String, apiKey: String) {
        sp.edit().putString("provider", provider).putString("api_key", apiKey).apply()
    }

    fun provider(): String? = sp.getString("provider", null)
    fun apiKey(): String? = sp.getString("api_key", null)
    fun hasAuth(): Boolean = !apiKey().isNullOrBlank()

    fun clear() {
        sp.edit().remove("provider").remove("api_key").apply()
    }
}
