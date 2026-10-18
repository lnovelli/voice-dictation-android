package com.ludo.voicedictation.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
    }

    val modelsDir: File
        get() {
            val dir = File(context.filesDir, "models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getModelDir(model: ModelInfo): File {
        return File(modelsDir, model.dirName)
    }

    fun isModelDownloaded(model: ModelInfo): Boolean {
        val dir = getModelDir(model)
        if (!dir.exists() || !dir.isDirectory) return false
        val enc = File(dir, model.encoderFileName)
        val dec = File(dir, model.decoderFileName)
        val joiner = File(dir, model.joinerFileName)
        val tokens = File(dir, model.tokensFileName)

        return enc.exists() && enc.length() > 1_000_000 &&
                dec.exists() && dec.length() > 100_000 &&
                joiner.exists() && joiner.length() > 100_000 &&
                tokens.exists() && tokens.length() > 100
    }

    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (step: String, progress: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Only the ASR model is needed. Legacy installed VAD files are ignored.
            // Download model archive
            val archiveFile = File(modelsDir, "${model.dirName}.tar.bz2")
            if (archiveFile.exists()) archiveFile.delete()

            onProgress("Downloading ${model.displayName}...", 10)
            downloadFileWithRedirects(model.archiveUrl, archiveFile) { currentBytes, totalBytes ->
                val pct = if (totalBytes > 0) {
                    10 + ((currentBytes * 75) / totalBytes).toInt()
                } else 10
                onProgress("Downloading ${model.displayName}...", pct)
            }

            // Extract model archive
            onProgress("Extracting model archive...", 88)
            extractTarBz2(archiveFile, modelsDir)

            // Cleanup archive
            if (archiveFile.exists()) archiveFile.delete()

            if (!isModelDownloaded(model)) {
                return@withContext Result.failure(Exception("Model extraction verification failed"))
            }

            onProgress("Model ready!", 100)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.displayName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun deleteModel(model: ModelInfo): Boolean {
        val dir = getModelDir(model)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else false
    }

    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 8) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "VoiceDictation-Android")

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val newUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (newUrl == null) throw Exception("Redirect without Location header")
                currentUrl = newUrl
                redirects++
                continue
            }
            return conn
        }
        throw Exception("Too many redirects")
    }

    private fun downloadFileWithRedirects(
        urlStr: String,
        destFile: File,
        onProgress: (current: Long, total: Long) -> Unit
    ) {
        val conn = openConnectionWithRedirects(urlStr)
        if (conn.responseCode !in 200..299) {
            throw Exception("HTTP ${conn.responseCode} while downloading from $urlStr")
        }
        val contentLength = conn.contentLengthLong

        val tempFile = File(destFile.parentFile, "${destFile.name}.download")
        if (tempFile.exists()) tempFile.delete()

        conn.inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalRead: Long = 0
                var lastReportTime = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val now = System.currentTimeMillis()
                    if (now - lastReportTime > 200) {
                        lastReportTime = now
                        onProgress(totalRead, contentLength)
                    }
                }
                output.flush()
            }
        }
        if (destFile.exists()) destFile.delete()
        if (!tempFile.renameTo(destFile)) {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun extractTarBz2(archiveFile: File, outputDir: File) {
        BufferedInputStream(archiveFile.inputStream()).use { bis ->
            BZip2CompressorInputStream(bis).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    val buffer = ByteArray(32768)
                    while (entry != null) {
                        val outputFile = File(outputDir, entry.name)
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { fos ->
                                var len: Int
                                while (tarIn.read(buffer).also { len = it } != -1) {
                                    fos.write(buffer, 0, len)
                                }
                                fos.flush()
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }
}
