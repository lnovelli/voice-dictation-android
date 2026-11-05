package com.ludo.voicedictation.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

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
        return hasModelFiles(model, getModelDir(model))
    }

    private fun hasModelFiles(model: ModelInfo, dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val enc = File(dir, model.encoderFileName)
        val dec = File(dir, model.decoderFileName)
        val joiner = File(dir, model.joinerFileName)
        val tokens = File(dir, model.tokensFileName)

        return enc.isFile && enc.length() > 1_000_000 &&
                dec.isFile && dec.length() > 100_000 &&
                joiner.isFile && joiner.length() > 100_000 &&
                tokens.isFile && tokens.length() > 100
    }

    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (step: String, progress: Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val jobContext = currentCoroutineContext()
        var staging: File? = null
        try {
            if (isModelDownloaded(model)) return@withContext Result.success(Unit)
            // Work in a private, unique staging directory, never in an installed model.
            val workDir = File(modelsDir, ".model-install-${UUID.randomUUID()}")
            check(workDir.mkdir()) { "Could not create model staging directory" }
            staging = workDir
            val archiveFile = File(workDir, "archive.tar.bz2")

            onProgress("Downloading ${model.displayName}...", 10)
            downloadFileWithRedirects(model.archiveUrl, archiveFile, model.archiveSha256, { jobContext.ensureActive() }) { currentBytes, totalBytes ->
                val pct = if (totalBytes > 0) {
                    10 + ((currentBytes * 75) / totalBytes).toInt()
                } else 10
                onProgress("Downloading ${model.displayName}...", pct)
            }

            onProgress("Extracting model archive...", 88)
            val stagedModel = File(workDir, "model")
            check(stagedModel.mkdir()) { "Could not create model staging directory" }
            extractTarBz2(archiveFile, stagedModel, model) { jobContext.ensureActive() }
            check(hasModelFiles(model, stagedModel)) { "Model extraction verification failed" }
            jobContext.ensureActive()

            // Keep any pre-existing partial directory intact instead of deleting user data.
            val target = getModelDir(model)
            val backup = if (target.exists()) {
                File(modelsDir, "${model.dirName}.preserved-${UUID.randomUUID()}").also {
                    check(target.renameTo(it)) { "Could not preserve existing model directory" }
                }
            } else null
            if (!stagedModel.renameTo(target)) {
                if (backup != null && !backup.renameTo(target)) {
                    throw IllegalStateException("Install failed; previous files preserved at ${backup.name}")
                }
                throw IllegalStateException("Could not install model files")
            }

            onProgress("Model ready!", 100)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.displayName}: ${e.message}", e)
            Result.failure(e)
        } finally {
            staging?.deleteRecursively()
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
        expectedSha256: String?,
        checkCancelled: () -> Unit,
        onProgress: (current: Long, total: Long) -> Unit
    ) {
        val conn = openConnectionWithRedirects(urlStr)
        try {
            if (conn.responseCode !in 200..299) {
                throw Exception("HTTP ${conn.responseCode} while downloading from $urlStr")
            }
            val contentLength = conn.contentLengthLong
            val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
            var totalRead = 0L
            conn.inputStream.use { input ->
              FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var lastReportTime = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    checkCancelled()
                    output.write(buffer, 0, bytesRead)
                    digest?.update(buffer, 0, bytesRead)
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
            check(contentLength < 0 || totalRead == contentLength) { "Incomplete model download" }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(actual.equals(expectedSha256, ignoreCase = true)) { "Model archive checksum mismatch" }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTarBz2(archiveFile: File, outputDir: File, model: ModelInfo, checkCancelled: () -> Unit) {
        val allowed = setOf(model.encoderFileName, model.decoderFileName, model.joinerFileName, model.tokensFileName)
        check(allowed.size == 4 && allowed.all { it.isNotEmpty() && File(it).name == it && it != "." && it != ".." })
        val extracted = mutableSetOf<String>()
        BufferedInputStream(archiveFile.inputStream()).use { bis ->
            BZip2CompressorInputStream(bis).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    val buffer = ByteArray(32768)
                    while (entry != null) {
                        checkCancelled()
                        // Only exact root/model filenames are admitted; never resolve tar paths.
                        val fileName = entry.name.removePrefix("${model.dirName}/")
                        if (entry.name == "${model.dirName}/$fileName" && fileName in allowed) {
                            // Commons Compress isFile() also accepts some special entries.
                            check((entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM) &&
                                entry.isCheckSumOK && extracted.add(fileName)) {
                                "Invalid or duplicate model archive entry: ${entry.name}"
                            }
                            val outputFile = File(outputDir, fileName)
                            FileOutputStream(outputFile).use { fos ->
                                var len: Int
                                while (tarIn.read(buffer).also { len = it } != -1) {
                                    checkCancelled()
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
        check(extracted == allowed) { "Model archive is missing required files" }
    }
}
