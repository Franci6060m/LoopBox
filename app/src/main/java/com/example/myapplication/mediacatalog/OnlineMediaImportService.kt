package com.example.myapplication.mediacatalog

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Imports a direct public media-file URL. It does not resolve video-site pages, extract hidden
 * player URLs, sign in, or bypass DRM/paywalls. The caller should pass a direct file candidate
 * returned by a discovery provider and honour its rights field before offering download.
 */
class OnlineMediaImportService(
    http: OkHttpClient,
    private val audioConverter: LocalVideoAudioConverter,
    private val cacheDir: File,
) {
    private val client = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Download the selected direct file as-is (e.g. MP4 video or MP3 audio). */
    suspend fun downloadDirectFile(url: String, output: File): File = withContext(Dispatchers.IO) {
        requireAllowedDirectFile(url)
        output.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get()
            .header("User-Agent", "LoopBox/1.0 media download")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Source returned HTTP ${response.code}" }
            val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            require(!type.contains("text/html")) { "This is a web page, not a direct media file" }
            val body = response.body
            require(body.contentLength() != 0L) { "The source returned an empty file" }
            body.byteStream().use { input ->
                FileOutputStream(output).use { target -> input.copyTo(target) }
            }
        }
        check(output.isFile && output.length() > 0L) { "Download did not produce a file" }
        output
    }

    /**
     * Download a direct video file and extract its audio track to M4A/AAC.
     * MP3 output is intentionally not claimed: Android Media3's built-in encoder targets AAC.
     */
    suspend fun downloadVideoAsAudio(url: String, outputM4a: File): File = withContext(Dispatchers.IO) {
        val source = File(cacheDir, "loopbox-import-${System.nanoTime()}.media")
        try {
            downloadDirectFile(url, source)
            audioConverter.extractAudio(Uri.fromFile(source), outputM4a)
        } finally {
            source.delete()
        }
    }

    private fun requireAllowedDirectFile(raw: String) {
        val uri = Uri.parse(raw)
        require(uri.scheme == "https" || uri.scheme == "http") { "Only HTTP(S) sources are supported" }
        val host = uri.host?.lowercase(Locale.ROOT) ?: error("Invalid media URL")
        val blocked = listOf(
            "netflix.com", "primevideo.com", "amazon.com", "amazon.co.uk",
            "sonypictures.com", "sonyliv.com", "disneyplus.com", "disney.com",
            "crunchyroll.com", "max.com", "hbomax.com", "hulu.com",
            "paramountplus.com", "peacocktv.com", "tv.apple.com",
        )
        require(blocked.none { host == it || host.endsWith(".$it") }) {
            "This subscription platform is not a direct-download source"
        }
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val supported = listOf(
            ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".flac", ".wav",
            ".mp4", ".m4v", ".webm", ".ogv", ".mkv",
        )
        require(supported.any(path::endsWith)) { "The URL does not look like a direct media file" }
    }
}
