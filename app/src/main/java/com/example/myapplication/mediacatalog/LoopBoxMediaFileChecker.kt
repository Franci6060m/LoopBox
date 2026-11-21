package com.example.myapplication.mediacatalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LoopBoxFileCheck(
    val playable: Boolean,
    val downloadable: Boolean,
    val finalUrl: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val message: String
)

/**
 * Checks a direct media-file candidate in the background before showing Stream/Download.
 * This does not resolve webpages, extract hidden player URLs, or bypass authentication/DRM.
 */
class LoopBoxMediaFileChecker(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(18, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun check(file: LoopBoxMediaFile): LoopBoxFileCheck = withContext(Dispatchers.IO) {
        if (file.rights != LoopBoxRights.REUSE_ALLOWED) {
            return@withContext rejected(file.url, "Reuse permission is not confirmed.")
        }
        val request = Request.Builder()
            .url(file.url)
            .head()
            .header("User-Agent", "LoopBox/1.0")
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Some public file hosts reject HEAD; retry with a one-byte range GET.
                    return@use null
                }
                val type = response.header("Content-Type")?.substringBefore(";")?.lowercase(Locale.ROOT)
                val length = response.header("Content-Length")?.toLongOrNull()
                if (type == "text/html" || type == "application/json") {
                    return@use rejected(file.url, "The link returns a webpage, not a media file.")
                }
                val extensionLooksMedia = isMediaExtension(file.url)
                val mimeLooksMedia = type?.startsWith("audio/") == true || type?.startsWith("video/") == true
                if (!extensionLooksMedia && !mimeLooksMedia) {
                    return@use rejected(file.url, "The server did not identify this as audio or video.")
                }
                LoopBoxFileCheck(true, true, response.request.url.toString(), type, length, "Verified direct media file.")
            }
        }.getOrNull()?.let { return@withContext it }

        val rangeRequest = Request.Builder().url(file.url).get()
            .header("Range", "bytes=0-0")
            .header("User-Agent", "LoopBox/1.0")
            .build()
        runCatching {
            http.newCall(rangeRequest).execute().use { response ->
                val type = response.header("Content-Type")?.substringBefore(";")?.lowercase(Locale.ROOT)
                val mediaMime = type?.startsWith("audio/") == true || type?.startsWith("video/") == true
                val mediaExt = isMediaExtension(file.url)
                if (response.isSuccessful && type != "text/html" && (mediaMime || mediaExt)) {
                    LoopBoxFileCheck(true, true, response.request.url.toString(), type, null, "Verified by range request.")
                } else rejected(file.url, "Could not verify a direct media response.")
            }
        }.getOrElse { rejected(file.url, it.message ?: "File check failed.") }
    }

    private fun isMediaExtension(url: String): Boolean {
        val path = url.substringBefore("?").lowercase(Locale.ROOT)
        return listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".flac", ".wav",
            ".mp4", ".m4v", ".webm", ".ogv", ".mkv").any(path::endsWith)
    }

    private fun rejected(url: String, reason: String) =
        LoopBoxFileCheck(false, false, url, null, null, reason)
}
