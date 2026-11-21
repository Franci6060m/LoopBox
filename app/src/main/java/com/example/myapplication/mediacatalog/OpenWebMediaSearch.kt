package com.example.myapplication.mediacatalog

import java.net.IDN
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

data class WebMediaFileCandidate(
    val name: String,
    val url: String,
    val mimeType: String?,
    val format: String,
    val sizeBytes: Long? = null,
    val quality: String? = null,
    /** UNKNOWN means the page did not provide a machine-readable reuse/download licence. */
    val rights: String,
)

data class WebMediaHit(
    val title: String,
    val description: String?,
    val artworkUrl: String?,
    val pageUrl: String,
    val sourceHost: String,
    val mediaFiles: List<WebMediaFileCandidate>,
)

/**
 * Open-web discovery through federated public SearXNG instances, followed by lightweight
 * inspection of publicly reachable result pages. Search is not tied to a fixed media catalogue.
 *
 * It deliberately excludes subscription/DRM platforms and never attempts login, cookies,
 * stream manifests, DRM keys, paywall circumvention, or hidden player APIs. A direct URL is
 * reported as a candidate; it is marked REUSE_ALLOWED only when the page exposes a recognised
 * Creative Commons/public-domain licence. Otherwise the rights state remains UNKNOWN.
 */
class OpenWebMediaSearch(
    http: OkHttpClient,
    private val searchInstances: List<String> = listOf(
        "https://searx.be",
        "https://searx.tiekoetter.com",
        "https://search.sapti.me",
    ),
) {
    private val client = http.newBuilder()
        .callTimeout(22, TimeUnit.SECONDS)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val blockedHosts = setOf(
        "netflix.com", "primevideo.com", "amazon.com", "amazon.co.uk",
        "sonypictures.com", "crunchyroll.com", "disneyplus.com", "disney.com",
        "max.com", "hbomax.com", "hulu.com", "paramountplus.com",
        "peacocktv.com", "apple.com", "tv.apple.com",
    )

    suspend fun search(query: String, kind: DiscoveryKind, limit: Int = 30): List<WebMediaHit> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        val count = limit.coerceIn(1, 40)
        return withContext(Dispatchers.IO) {
            val pages = coroutineScope {
                searchInstances.distinct().map { instance ->
                    async { searchInstance(instance, term, kind, count) }
                }.awaitAll().flatten()
            }.distinctBy { it.url }.take(count)
            coroutineScope {
                pages.map { result ->
                    async { inspectResult(result.url) }
                }.awaitAll().filterNotNull().distinctBy { it.pageUrl }.take(count)
            }
        }
    }

    private data class SearchResult(val title: String, val url: String)

    private fun searchInstance(base: String, query: String, kind: DiscoveryKind, limit: Int): List<SearchResult> {
        val url = base.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("search")
            ?.addQueryParameter("q", query + when (kind) {
                DiscoveryKind.MUSIC -> " audio music mp3"
                DiscoveryKind.MOVIE -> " movie video"
                DiscoveryKind.SERIES -> " tv series episodes"
            })
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("safesearch", "1")
            ?.addQueryParameter("language", "all")
            ?.build() ?: return emptyList()
        return try {
            val request = Request.Builder().url(url).header("User-Agent", "LoopBox/1.0 (open media discovery)").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val array = org.json.JSONObject(response.body.string()).optJSONArray("results") ?: return emptyList()
                buildList {
                    for (i in 0 until minOf(array.length(), limit)) {
                        val item = array.optJSONObject(i) ?: continue
                        val link = item.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                        if (isBlocked(link)) continue
                        add(SearchResult(item.optString("title"), link))
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun inspectResult(pageUrl: String): WebMediaHit? = try {
        if (isBlocked(pageUrl)) return null
        val connection = Jsoup.connect(pageUrl)
            .userAgent("LoopBox/1.0 (open media discovery)")
            .timeout(10000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
        val response = connection.execute()
        if (response.statusCode() !in 200..299) return null
        val doc = response.parse()
        val finalUrl = response.url().toString()
        if (isBlocked(finalUrl)) return null
        val host = finalUrl.toHttpUrlOrNull()?.host ?: return null
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: doc.title().takeIf { it.isNotBlank() } ?: host
        val description = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
        val image = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { doc.baseUri().toHttpUrlOrNull()?.resolve(it)?.toString() }
        val licence = sequenceOf(
            doc.selectFirst("link[rel=license]")?.attr("href"),
            doc.selectFirst("meta[name=license]")?.attr("content"),
            doc.selectFirst("meta[name=dcterms.rights]")?.attr("content"),
        ).filterNotNull().firstOrNull { it.isNotBlank() }
        val rights = if (isRecognisedOpenLicence(licence)) "REUSE_ALLOWED" else "UNKNOWN"
        val files = doc.select("a[href], source[src], video[src], audio[src], video source[src], audio source[src]")
            .mapNotNull { element ->
                val raw = if (element.hasAttr("href")) element.attr("abs:href") else element.attr("abs:src")
                val resolved = raw.toHttpUrlOrNull() ?: return@mapNotNull null
                if (isBlocked(resolved.toString())) return@mapNotNull null
                val path = resolved.encodedPath.lowercase(Locale.ROOT)
                val ext = path.substringAfterLast('.', "")
                val format = when (ext) {
                    "mp3" -> "MP3"
                    "m4a" -> "M4A"
                    "aac" -> "AAC"
                    "ogg", "oga" -> "OGG"
                    "flac" -> "FLAC"
                    "wav" -> "WAV"
                    "mp4", "m4v" -> "MP4"
                    "webm" -> "WEBM"
                    "ogv" -> "OGV"
                    "mkv" -> "MKV"
                    else -> return@mapNotNull null
                }
                WebMediaFileCandidate(
                    name = resolved.encodedPath.substringAfterLast('/').ifBlank { "$title.$ext" },
                    url = resolved.toString(),
                    mimeType = element.attr("type").takeIf { it.isNotBlank() },
                    format = format,
                    quality = qualityHint(resolved.encodedPath),
                    rights = rights,
                )
            }.distinctBy { it.url }.take(12)
        WebMediaHit(title, description, image, finalUrl, host, files)
    } catch (_: Exception) { null }

    private fun isBlocked(raw: String): Boolean {
        val host = raw.toHttpUrlOrNull()?.host ?: return true
        val ascii = IDN.toASCII(host).lowercase(Locale.ROOT)
        return blockedHosts.any { ascii == it || ascii.endsWith(".$it") }
    }

    private fun isRecognisedOpenLicence(value: String?): Boolean {
        val v = value?.lowercase(Locale.ROOT).orEmpty()
        return v.contains("creativecommons.org/publicdomain/") ||
            v.contains("creativecommons.org/licenses/by/") ||
            v.contains("creativecommons.org/licenses/by-sa/") ||
            v.contains("creativecommons.org/zero/")
    }

    private fun qualityHint(path: String): String? = when {
        path.contains("2160") || path.contains("4k") -> "4K"
        path.contains("1080") -> "1080p"
        path.contains("720") -> "720p"
        path.contains("480") -> "480p"
        path.contains("320") -> "320 kbps"
        else -> null
    }
}
