package com.opencodelocal.app.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipFile

/**
 * 首次啟動下載器：把 40~60MB 的 android-aarch64.zip
 * 下載到 App 沙盒並解壓，不用裝 Termux、不用 root。
 * 進度回報給圖形 UI 的進度條（不是文字 log）。
 */
class OpencodeInstaller(
    private val context: Context,
    private val backend: OpencodeBackend
) {
    sealed interface Progress {
        data class Downloading(val percent: Int) : Progress
        data object Extracting : Progress
        data object Done : Progress
        data class Failed(val reason: String) : Progress
    }

    suspend fun install(onProgress: (Progress) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val binDir = backend.binDir()
                val zipFile = File(context.cacheDir, "opencode-android.zip")

                onProgress(Progress.Downloading(0))
                URL(OpencodeConfig.BINARY_ZIP_URL).openStream().use { input ->
                    val conn = URL(OpencodeConfig.BINARY_ZIP_URL).openConnection()
                    val total = conn.contentLengthLong.coerceAtLeast(1)
                    FileOutputStream(zipFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            onProgress(Progress.Downloading(((done * 100) / total).toInt().coerceIn(0, 100)))
                        }
                    }
                }

                onProgress(Progress.Extracting)
                // 簡易 zip 解壓（含 opencode / opencode.bin / .so）
                java.util.zip.ZipInputStream(zipFile.inputStream()).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val outFile = File(binDir, File(entry.name).name)
                        if (!entry.isDirectory) {
                            outFile.outputStream().use { out -> zin.copyTo(out) }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
                File(binDir, OpencodeConfig.BIN_WRAPPER).setExecutable(true)
                File(binDir, OpencodeConfig.BIN_MAIN).setExecutable(true)

                // .so 一起放到 lib 讓 linker 找得到
                binDir.listFiles { f -> f.extension == "so" }?.forEach { so ->
                    so.copyTo(File(context.applicationInfo.nativeLibraryDir, so.name), overwrite = true)
                }

                zipFile.delete()
                onProgress(Progress.Done)
            } catch (e: Exception) {
                onProgress(Progress.Failed(e.message ?: "下載失敗，請檢查網路"))
            }
        }
    }
}
