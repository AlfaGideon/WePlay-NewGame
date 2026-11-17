package com.weplay.cardtycoon.helper.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.weplay.cardtycoon.helper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zero-secret OTA updater using public GitHub Releases API (no API token required!).
 *
 * Works seamlessly because all CI releases are signed with the same universal signing key
 * (app/signing/universal-ota.p12), so Android allows direct in-place updates without reinstalling.
 */
object OtaUpdater {

    data class UpdateInfo(
        val tagName: String,
        val remoteVersionCode: Int?,
        val releaseNotes: String,
        val apkDownloadUrl: String,
        val apkSizeBytes: Long,
        val isNewer: Boolean
    )

    suspend fun checkForUpdate(
        apiUrl: String = BuildConfig.OTA_RELEASES_API
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CardTycoonHelper-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                error("Сервер обновлений вернул HTTP $code")
            }
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = JSONObject(jsonText)
            val tag = root.optString("tag_name", "").trim()
            val body = root.optString("body", "").trim()
            val assets = root.optJSONArray("assets")

            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) {
                error("В последнем релизе ($tag) не найден APK-файл")
            }

            val parsedVersionCode = Regex("versionCode:\\s*(\\d+)")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val isNewer = when {
                parsedVersionCode != null -> parsedVersionCode > BuildConfig.VERSION_CODE
                else -> compareSemver(tag.removePrefix("v"), BuildConfig.VERSION_NAME) > 0
            }

            UpdateInfo(
                tagName = tag,
                remoteVersionCode = parsedVersionCode,
                releaseNotes = body.ifBlank { "Обновление до $tag" },
                apkDownloadUrl = apkUrl,
                apkSizeBytes = apkSize,
                isNewer = isNewer
            )
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(updatesDir, "CardTycoonHelper-update.apk")
            if (outFile.exists()) outFile.delete()

            var currentUrl = downloadUrl
            var conn: HttpURLConnection? = null
            for (hop in 0 until 6) {
                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "CardTycoonHelper-Android/${BuildConfig.VERSION_NAME}")
                }
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) error("Пустой редирект при загрузке APK (HTTP $code)")
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    continue
                }
                conn = c
                break
            }
            val finalConn = conn ?: error("Слишком много редиректов при загрузке APK")
            val status = finalConn.responseCode
            if (status !in 200..299) {
                finalConn.disconnect()
                error("Ошибка загрузки APK (HTTP $status)")
            }

            val total = finalConn.contentLengthLong.coerceAtLeast(1L)
            finalConn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
            finalConn.disconnect()
            outFile
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    internal fun compareSemver(a: String, b: String): Int {
        val pa = a.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
