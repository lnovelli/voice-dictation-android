package com.ludo.voicedictation.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ludo.voicedictation.R
import com.ludo.voicedictation.VoiceDictationApp
import com.ludo.voicedictation.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val UPDATE_NOTIFICATION_ID = 3001
    private const val PRIMARY_VERSION_URL =
        "https://example.invalid/download/voice-dictation-version.json"
    private const val FALLBACK_VERSION_URL =
        "https://example.invalid/download/voice-dictation-version.json"
    const val DEFAULT_APK_URL = "https://example.invalid/download/voice-dictation.apk"

    fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    fun checkForUpdates(
        context: Context,
        onResult: (hasUpdate: Boolean, remoteVersionCode: Long, remoteVersionName: String, downloadUrl: String, notes: String, error: String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var jsonText: String? = null
            var lastError: String? = null

            for (urlStr in listOf(PRIMARY_VERSION_URL, FALLBACK_VERSION_URL)) {
                try {
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 10000
                    conn.connect()

                    if (conn.responseCode in 200..299) {
                        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                            jsonText = reader.readText()
                        }
                        break
                    } else {
                        lastError = "Server returned HTTP ${conn.responseCode}"
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Connection error"
                }
            }

            if (jsonText != null) {
                try {
                    val json = JSONObject(jsonText!!)
                    val remoteCode = json.optLong("version_code", 1L)
                    val remoteName = json.optString("version_name", "1.0.0")
                    val downloadUrl = json.optString("download_url", DEFAULT_APK_URL)
                    val notes = json.optString("release_notes", "")
                    val currentCode = getCurrentVersionCode(context)

                    val hasUpdate = remoteCode > currentCode
                    withContext(Dispatchers.Main) {
                        onResult(hasUpdate, remoteCode, remoteName, downloadUrl, notes, null)
                    }
                    return@launch
                } catch (e: Exception) {
                    lastError = "Failed to parse update manifest: ${e.message}"
                }
            }

            withContext(Dispatchers.Main) {
                onResult(false, 0L, "", "", "", lastError ?: "Failed to check for updates")
            }
        }
    }

    fun downloadAndPromptInstall(
        context: Context,
        downloadUrl: String = DEFAULT_APK_URL,
        onProgress: ((progress: Int) -> Unit)? = null,
        onError: ((error: String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    throw Exception("Server returned HTTP ${conn.responseCode}")
                }

                val fileLength = conn.contentLength
                val apkDir = File(context.cacheDir, "apk_updates")
                if (!apkDir.exists()) apkDir.mkdirs()
                val apkFile = File(apkDir, "voice-dictation-update.apk")
                if (apkFile.exists()) apkFile.delete()

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val data = ByteArray(8192)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = 0
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            output.write(data, 0, count)
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                if (progress >= lastProgress + 5) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress?.invoke(progress)
                                    }
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    launchInstallIntent(context, apkFile)
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update download failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Download failed")
                }
            }
        }
    }

    fun launchInstallIntent(context: Context, apkFile: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }
}
