package com.ludo.voicedictation.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val OWNER = "lnovelli"
    private const val REPOSITORY = "voice-dictation-android"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"
    private const val APK_ASSET_NAME = "voice-dictation.apk"
    private const val MANIFEST_ASSET_NAME = "voice-dictation-version.json"
    private const val MAX_APK_BYTES = 150L * 1024 * 1024
    private const val MAX_API_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_REDIRECTS = 5
    private val allowedAssetHosts = setOf(
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com"
    )
    private val sha256Pattern = Regex("[a-fA-F0-9]{64}")

    private data class ReleaseInfo(
        val versionCode: Long,
        val versionName: String,
        val downloadUrl: String,
        val notes: String,
        val sha256: String
    )

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
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    fun checkForUpdates(
        context: Context,
        onResult: (
            hasUpdate: Boolean,
            remoteVersionCode: Long,
            remoteVersionName: String,
            downloadUrl: String,
            notes: String,
            sha256: String,
            error: String?
        ) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val release = fetchLatestRelease()
                val hasUpdate = release.versionCode > getCurrentVersionCode(context)
                withContext(Dispatchers.Main) {
                    onResult(
                        hasUpdate,
                        release.versionCode,
                        release.versionName,
                        release.downloadUrl,
                        release.notes,
                        release.sha256,
                        null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub release check failed", e)
                withContext(Dispatchers.Main) {
                    onResult(false, 0L, "", "", "", "", e.message ?: "Failed to check GitHub Releases")
                }
            }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val release = JSONObject(readHttpsText(LATEST_RELEASE_API, MAX_API_BYTES))
        val tag = release.optString("tag_name")
        if (!Regex("v[0-9]+(?:\\.[0-9]+){1,3}").matches(tag)) {
            throw IOException("Latest GitHub release has an invalid version tag")
        }

        val (manifestUrl, manifestSize) = findReleaseAsset(release, MANIFEST_ASSET_NAME, tag)
        val (apkUrl, apkSize) = findReleaseAsset(release, APK_ASSET_NAME, tag)
        if (manifestSize !in 1..MAX_MANIFEST_BYTES.toLong()) {
            throw IOException("GitHub release manifest has an invalid size")
        }
        if (apkSize !in 1..MAX_APK_BYTES) {
            throw IOException("GitHub release APK has an invalid size")
        }

        val manifest = JSONObject(readHttpsText(manifestUrl, MAX_MANIFEST_BYTES))
        val versionCode = manifest.optLong("version_code", 0L)
        val versionName = manifest.optString("version_name", "")
        if (versionCode <= 0 || !Regex("[0-9]+(?:\\.[0-9]+){1,3}").matches(versionName) || tag != "v$versionName") {
            throw IOException("GitHub release version metadata is invalid")
        }
        if (manifest.optString("download_url") != apkUrl) {
            throw IOException("GitHub release APK URL does not match its metadata")
        }
        val sha256 = manifest.optString("sha256", "").lowercase(Locale.ROOT)
        if (!sha256Pattern.matches(sha256)) {
            throw IOException("GitHub release SHA-256 is missing or invalid")
        }
        return ReleaseInfo(
            versionCode = versionCode,
            versionName = versionName,
            downloadUrl = apkUrl,
            notes = manifest.optString("release_notes", release.optString("body", "")),
            sha256 = sha256
        )
    }

    private fun findReleaseAsset(release: JSONObject, assetName: String, tag: String): Pair<String, Long> {
        val assets = release.optJSONArray("assets") ?: throw IOException("GitHub release has no assets")
        var selected: JSONObject? = null
        for (index in 0 until assets.length()) {
            val candidate = assets.optJSONObject(index) ?: continue
            if (candidate.optString("name") == assetName) {
                if (selected != null) throw IOException("GitHub release has duplicate $assetName assets")
                selected = candidate
            }
        }
        val asset = selected ?: throw IOException("GitHub release is missing $assetName")
        val url = asset.optString("browser_download_url", "")
        val uri = try { URI(url) } catch (_: Exception) { throw IOException("GitHub release asset URL is invalid") }
        val expectedPath = "/$OWNER/$REPOSITORY/releases/download/$tag/$assetName"
        if (uri.scheme != "https" || uri.host != "github.com" ||
            (uri.port != -1 && uri.port != 443) || uri.rawPath != expectedPath ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw IOException("GitHub release asset URL is not trusted")
        }
        return url to asset.optLong("size", -1L)
    }

    private fun readHttpsText(url: String, maxBytes: Int): String {
        val connection = openGithubConnection(url)
        try {
            val length = connection.contentLengthLong
            if (length > maxBytes) throw IOException("GitHub response is too large")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > maxBytes) throw IOException("GitHub response is too large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun openGithubConnection(startUrl: String): HttpsURLConnection {
        var current = try { URI(startUrl) } catch (_: Exception) { throw IOException("Invalid GitHub URL") }
        val allowedHosts = when (current.host) {
            "api.github.com" -> setOf("api.github.com")
            "github.com" -> allowedAssetHosts
            else -> throw IOException("Untrusted GitHub host")
        }

        repeat(MAX_REDIRECTS + 1) {
            if (current.scheme != "https" || current.host !in allowedHosts ||
                current.rawUserInfo != null || current.rawFragment != null) {
                throw IOException("Untrusted GitHub redirect")
            }
            val connection = try {
                URL(current.toASCIIString()).openConnection() as? HttpsURLConnection
                    ?: throw IOException("HTTPS connection required")
            } catch (e: Exception) {
                throw IOException("Cannot open GitHub connection", e)
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "VoiceDictation-Android")
            if (current.host == "api.github.com") {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            val code = try { connection.responseCode } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("GitHub redirect has no target")
                current = try { current.resolve(location) } catch (_: Exception) {
                    throw IOException("GitHub redirect target is invalid")
                }
            } else if (code in 200..299) {
                return connection
            } else {
                connection.disconnect()
                throw IOException("GitHub returned HTTP $code")
            }
        }
        throw IOException("Too many GitHub redirects")
    }

    fun downloadAndPromptInstall(
        context: Context,
        downloadUrl: String,
        expectedSha256: String,
        expectedVersionCode: Long,
        onProgress: ((progress: Int) -> Unit)? = null,
        onError: ((error: String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isTrustedApkUrl(downloadUrl)) throw IOException("Untrusted GitHub release APK URL")
                val sha256 = expectedSha256.lowercase(Locale.ROOT)
                if (!sha256Pattern.matches(sha256) || expectedVersionCode <= getCurrentVersionCode(context)) {
                    throw IOException("GitHub release version metadata is invalid or stale")
                }
                val apkFile = downloadVerifiedApk(context, downloadUrl, sha256, expectedVersionCode) { progress ->
                    withContext(Dispatchers.Main) { onProgress?.invoke(progress) }
                }
                withContext(Dispatchers.Main) {
                    launchInstallIntent(context, apkFile)
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "GitHub update download failed", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Download failed")
                }
            }
        }
    }

    private fun isTrustedApkUrl(value: String): Boolean {
        val uri = try { URI(value) } catch (_: Exception) { return false }
        return uri.scheme == "https" && uri.host == "github.com" &&
            (uri.port == -1 || uri.port == 443) &&
            Regex("/$OWNER/$REPOSITORY/releases/download/v[0-9]+(?:\\.[0-9]+){1,3}/$APK_ASSET_NAME")
                .matches(uri.rawPath ?: "") &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }

    private suspend fun downloadVerifiedApk(
        context: Context,
        url: String,
        expectedSha256: String,
        expectedVersionCode: Long,
        onProgress: suspend (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apkDir = File(context.cacheDir, "apk_updates")
        if (!apkDir.isDirectory && !apkDir.mkdirs()) throw IOException("Cannot create APK cache directory")
        val temporary = File.createTempFile("voice-dictation-", ".part", apkDir)
        try {
            val connection = openGithubConnection(url)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val expectedLength = connection.contentLengthLong
                if (expectedLength == 0L || expectedLength > MAX_APK_BYTES) {
                    throw IOException("GitHub release APK has an invalid size")
                }
                var total = 0L
                var lastProgress = -5
                FileOutputStream(temporary).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES || (expectedLength > 0 && total > expectedLength)) {
                                throw IOException("GitHub release APK exceeds its declared size")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (expectedLength > 0) {
                                val progress = (total * 100 / expectedLength).toInt().coerceIn(0, 100)
                                if (progress >= lastProgress + 5) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
                if (total <= 0 || (expectedLength > 0 && total != expectedLength)) {
                    throw IOException("Incomplete GitHub APK download")
                }
            } finally {
                connection.disconnect()
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != expectedSha256) throw IOException("APK SHA-256 does not match the release manifest")
            validateDownloadedApk(context, temporary, expectedVersionCode)

            val verifiedApk = File(apkDir, "$actualSha256.apk")
            if (verifiedApk.exists()) {
                if (verifiedApk.length() != temporary.length()) throw IOException("Cached verified APK is inconsistent")
                temporary.delete()
            } else if (!temporary.renameTo(verifiedApk)) {
                throw IOException("Cannot save verified APK")
            }
            apkDir.listFiles()?.filter { it != verifiedApk && it.extension == "apk" }?.forEach { it.delete() }
            verifiedApk
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun validateDownloadedApk(context: Context, file: File, expectedVersionCode: Long) {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Downloaded file is not a valid APK")
        if (archive.packageName != context.packageName) throw IOException("Downloaded APK package does not match")
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        if (archiveVersion != expectedVersionCode) throw IOException("Downloaded APK version does not match its release")
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        if (!hasCompatibleSigner(installed, archive)) throw IOException("Downloaded APK signer does not match this installation")
    }

    @Suppress("DEPRECATION")
    private fun hasCompatibleSigner(installed: PackageInfo, candidate: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val currentInfo = installed.signingInfo ?: return false
            val candidateInfo = candidate.signingInfo ?: return false
            val currentSigners = currentInfo.apkContentsSigners?.toSet().orEmpty()
            val candidateSigners = candidateInfo.apkContentsSigners?.toSet().orEmpty()
            if (currentSigners.isEmpty() || candidateSigners.isEmpty()) return false
            if (currentSigners == candidateSigners) return true
            return !currentInfo.hasMultipleSigners() && !candidateInfo.hasMultipleSigners() &&
                candidateInfo.signingCertificateHistory?.toSet()?.containsAll(currentSigners) == true
        }
        val currentSigners = installed.signatures?.toSet().orEmpty()
        return currentSigners.isNotEmpty() && currentSigners == candidate.signatures?.toSet()
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
