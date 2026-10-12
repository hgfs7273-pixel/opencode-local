package com.opencodelocal.app.backend

/**
 * 本地後端設定。
 * 核心概念：手機自己跑 `opencode serve` 在 127.0.0.1:4096，
 * App 的圖形 UI 用 HTTP 跟它講話，不連電腦、不開網頁終端。
 *
 * 二進位來源（免 glibc、最適合包進 App）：
 *   guysoft/opencode-termux 的 android-aarch64.zip
 *   （Bionic 原生編譯，可直接在 App 私有目錄執行）
 */
object OpencodeConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 4096
    const val BASE_URL = "http://127.0.0.1:4096"
    const val HEALTH_PATH = "/global/health"

    // 首次啟動下載位置（GitHub Release，直連不經第三方）
    const val BINARY_ZIP_URL =
        "https://github.com/guysoft/opencode-termux/releases/latest/download/opencode-android-aarch64.zip"

    const val BIN_DIR = "opencode-bin"
    const val BIN_WRAPPER = "opencode"
    const val BIN_MAIN = "opencode.bin"
}
