package com.example.myapplication.mediacatalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads only a direct file that has passed LoopBoxMediaFileChecker and has explicit reuse
 * permission. Call this from a WorkManager worker for long downloads in production.
 */
class LoopBoxMediaDownloadService(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    suspend fun download(file: LoopBoxMediaFile, checked: LoopBoxFileCheck, destination: File): File =
        withContext(Dispatchers.IO) {
            require(file.rights == LoopBoxRights.REUSE_ALLOWED) {
                "This source does not have confirmed reuse permission."
            }
            require(checked.playable && checked.downloadable) { "The file has not passed the background file check." }
            require(checked.finalUrl.startsWith("https://") || checked.finalUrl.startsWith("http://")) {
                "Unsupported media URL."
            }
            destination.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(checked.finalUrl)
                .get()
                .header("User-Agent", "LoopBox/1.0")
                .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                check(!contentType.contains("text/html")) { "The server returned a webpage, not media." }
                val body = requireNotNull(response.body) { "Empty response." }
                FileOutputStream(destination).use { output -> body.byteStream().use { it.copyTo(output) } }
            }
            check(destination.isFile && destination.length() > 0L) { "Downloaded file is empty." }
            destination
        }
}
