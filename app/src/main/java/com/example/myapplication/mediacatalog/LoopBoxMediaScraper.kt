package com.example.myapplication.mediacatalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class LoopBoxMediaKind { MUSIC, MOVIE, SERIES }

enum class LoopBoxRights { REUSE_ALLOWED, UNKNOWN, NOT_ALLOWED }

data class LoopBoxMediaFile(
    val name: String,
    val url: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val rights: LoopBoxRights,
    val source: String
)

data class LoopBoxMediaItem(
    val id: String,
    val title: String,
    val kind: LoopBoxMediaKind,
    val description: String = "",
    val artworkUrl: String? = null,
    val detailUrl: String? = null,
    val year: String? = null,
    val source: String,
    val files: List<LoopBoxMediaFile> = emptyList()
) {
    val canStream: Boolean get() = files.any { it.rights == LoopBoxRights.REUSE_ALLOWED }
    val canDownload: Boolean get() = canStream
}

/**
 * Discovery scraper for LoopBox.
 *
 * Metadata sources:
 * - iTunes Search API for music discovery (metadata/previews only).
 * - TVmaze for TV series metadata.
 * - Cinemeta for movie metadata.
 * - Internet Archive for public-domain / openly licensed media and direct files.
 *
 * A metadata hit is never represented as a downloadable file. Only Internet Archive
 * items with an explicitly recognised reuse licence are eligible for file verification.
 */
class LoopBoxMediaScraper(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun loadHomePage(perSection: Int = 12): Map<LoopBoxMediaKind, List<LoopBoxMediaItem>> =
        coroutineScope {
            val music = async { discoverMusic("popular", perSection) }
            val movies = async { discoverMovies("popular", perSection) }
            val series = async { discoverSeries("popular", perSection) }
            mapOf(
                LoopBoxMediaKind.MUSIC to music.await(),
                LoopBoxMediaKind.MOVIE to movies.await(),
                LoopBoxMediaKind.SERIES to series.await()
            )
        }

    suspend fun search(query: String, kind: LoopBoxMediaKind, limit: Int = 20): List<LoopBoxMediaItem> {
        require(query.isNotBlank()) { "Enter a search term." }
        return when (kind) {
            LoopBoxMediaKind.MUSIC -> discoverMusic(query, limit)
            LoopBoxMediaKind.MOVIE -> discoverMovies(query, limit)
            LoopBoxMediaKind.SERIES -> discoverSeries(query, limit)
        }
    }

    private suspend fun discoverMusic(query: String, limit: Int): List<LoopBoxMediaItem> =
        withContext(Dispatchers.IO) {
            val encoded = enc(if (query == "popular") "top songs" else query)
            val itunes = getJson("https://itunes.apple.com/search?term=$encoded&entity=song&limit=$limit")
            val metadata = mutableListOf<LoopBoxMediaItem>()
            val results = itunes.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val row = results.optJSONObject(i) ?: continue
                metadata += LoopBoxMediaItem(
                    id = "itunes:${row.optLong("trackId")}",
                    title = row.optString("trackName", "Unknown track"),
                    kind = LoopBoxMediaKind.MUSIC,
                    description = listOf(row.optString("artistName"), row.optString("collectionName"))
                        .filter { it.isNotBlank() }.joinToString(" • "),
                    artworkUrl = row.optString("artworkUrl100").takeIf { it.startsWith("http") },
                    detailUrl = row.optString("trackViewUrl").takeIf { it.startsWith("http") },
                    source = "iTunes",
                    files = emptyList() // iTunes preview URLs are not treated as downloadable files.
                )
            }
            val archive = searchArchive(query, "audio", limit)
            (metadata + archive).distinctBy { it.id }.take(limit)
        }

    private suspend fun discoverMovies(query: String, limit: Int): List<LoopBoxMediaItem> =
        withContext(Dispatchers.IO) {
            val archiveQuery = if (query == "popular") "mediatype:movies AND (licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*)" else
                "mediatype:movies AND (title:\"${escapeArchive(query)}\" OR description:\"${escapeArchive(query)}\")"
            val archive = searchArchiveQuery(archiveQuery, LoopBoxMediaKind.MOVIE, limit)
            val cinemeta = runCatching {
                val slug = enc(if (query == "popular") "popular" else query)
                val json = getJson("https://v3-cinemeta.strem.io/catalog/movie/top/search=$slug.json")
                parseCinemeta(json, LoopBoxMediaKind.MOVIE, limit)
            }.getOrDefault(emptyList())
            (archive + cinemeta).distinctBy { it.id }.take(limit)
        }

    private suspend fun discoverSeries(query: String, limit: Int): List<LoopBoxMediaItem> =
        withContext(Dispatchers.IO) {
            val tvQuery = if (query == "popular") "drama" else query
            val json = getJson("https://api.tvmaze.com/search/shows?q=${enc(tvQuery)}")
            val rows = json.optJSONArray("") ?: json as? JSONArray ?: JSONArray()
            val items = mutableListOf<LoopBoxMediaItem>()
            for (i in 0 until minOf(rows.length(), limit)) {
                val hit = rows.optJSONObject(i) ?: continue
                val show = hit.optJSONObject("show") ?: hit
                val image = show.optJSONObject("image")
                items += LoopBoxMediaItem(
                    id = "tvmaze:${show.optInt("id")}",
                    title = show.optString("name", "Unknown series"),
                    kind = LoopBoxMediaKind.SERIES,
                    description = cleanHtml(show.optString("summary")),
                    artworkUrl = image?.optString("medium")?.takeIf { it.startsWith("http") },
                    detailUrl = show.optString("url").takeIf { it.startsWith("http") },
                    year = show.optString("premiered").take(4).takeIf { it.length == 4 },
                    source = "TVmaze",
                    files = emptyList()
                )
            }
            val archive = searchArchiveQuery(
                if (query == "popular") "mediatype:movies AND subject:television" else
                    "mediatype:movies AND title:\"${escapeArchive(query)}\"",
                LoopBoxMediaKind.SERIES,
                limit
            )
            (items + archive).distinctBy { it.id }.take(limit)
        }

    private suspend fun searchArchive(query: String, mediaType: String, limit: Int): List<LoopBoxMediaItem> {
        val q = if (query == "popular") {
            "mediatype:$mediaType AND (licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*)"
        } else {
            "mediatype:$mediaType AND (title:\"${escapeArchive(query)}\" OR description:\"${escapeArchive(query)}\")"
        }
        val kind = if (mediaType == "audio") LoopBoxMediaKind.MUSIC else LoopBoxMediaKind.MOVIE
        return searchArchiveQuery(q, kind, limit)
    }

    private suspend fun searchArchiveQuery(
        query: String,
        kind: LoopBoxMediaKind,
        limit: Int
    ): List<LoopBoxMediaItem> = withContext(Dispatchers.IO) {
        val url = "https://archive.org/advancedsearch.php?q=${enc(query)}" +
            "&fl[]=identifier,title,description,year,licenseurl&rows=$limit&page=1&output=json"
        val json = getJson(url)
        val docs = json.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        val items = mutableListOf<LoopBoxMediaItem>()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = doc.optString("identifier")
            if (identifier.isBlank()) continue
            val licence = doc.optString("licenseurl")
            val rights = rightsFromLicence(licence)
            val files = if (rights == LoopBoxRights.REUSE_ALLOWED) {
                runCatching { loadArchiveFiles(identifier, kind) }.getOrDefault(emptyList())
            } else emptyList()
            items += LoopBoxMediaItem(
                id = "archive:$identifier",
                title = doc.optString("title", identifier),
                kind = kind,
                description = doc.optString("description").take(700),
                artworkUrl = "https://archive.org/services/img/$identifier",
                detailUrl = "https://archive.org/details/$identifier",
                year = doc.optString("year").takeIf { it.isNotBlank() },
                source = "Internet Archive",
                files = files
            )
        }
        items
    }

    private suspend fun loadArchiveFiles(identifier: String, kind: LoopBoxMediaKind): List<LoopBoxMediaFile> {
        val json = getJson("https://archive.org/metadata/${enc(identifier)}")
        val metadata = json.optJSONObject("metadata") ?: JSONObject()
        val licence = metadata.optString("licenseurl")
        if (rightsFromLicence(licence) != LoopBoxRights.REUSE_ALLOWED) return emptyList()
        val rows = json.optJSONArray("files") ?: JSONArray()
        val output = mutableListOf<LoopBoxMediaFile>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name")
            val lower = name.lowercase()
            val allowed = when (kind) {
                LoopBoxMediaKind.MUSIC -> listOf(".mp3", ".m4a", ".ogg", ".flac", ".wav").any(lower::endsWith)
                LoopBoxMediaKind.MOVIE, LoopBoxMediaKind.SERIES ->
                    listOf(".mp4", ".m4v", ".webm", ".ogv", ".mkv").any(lower::endsWith)
            }
            if (!allowed || row.optString("source") == "original" && row.optString("private") == "true") continue
            val encodedName = name.split("/").joinToString("/") { enc(it) }
            output += LoopBoxMediaFile(
                name = name,
                url = "https://archive.org/download/${enc(identifier)}/$encodedName",
                mimeType = row.optString("format").takeIf { it.isNotBlank() },
                sizeBytes = row.optString("size").toLongOrNull(),
                rights = LoopBoxRights.REUSE_ALLOWED,
                source = "Internet Archive"
            )
        }
        output
    }

    private fun parseCinemeta(json: JSONObject, kind: LoopBoxMediaKind, limit: Int): List<LoopBoxMediaItem> {
        val metas = json.optJSONObject("metas")?.optJSONArray("metas") ?: JSONArray()
        val output = mutableListOf<LoopBoxMediaItem>()
        for (i in 0 until minOf(metas.length(), limit)) {
            val row = metas.optJSONObject(i) ?: continue
            output += LoopBoxMediaItem(
                id = "cinemeta:${row.optString("id")}",
                title = row.optString("name", "Untitled"),
                kind = kind,
                description = row.optString("description").take(700),
                artworkUrl = row.optString("poster").takeIf { it.startsWith("http") },
                detailUrl = row.optString("imdb_id").takeIf { it.startsWith("http") },
                year = row.optString("year").takeIf { it.isNotBlank() },
                source = "Cinemeta",
                files = emptyList()
            )
        }
        return output
    }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "LoopBoxMediaDiscovery/1.0").get().build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Discovery source returned HTTP ${response.code}" }
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun rightsFromLicence(value: String): LoopBoxRights {
        val v = value.lowercase()
        return when {
            v.contains("creativecommons.org/licenses/by/") ||
            v.contains("creativecommons.org/licenses/by-sa/") ||
            v.contains("creativecommons.org/publicdomain/zero/") ||
            v.contains("creativecommons.org/publicdomain/mark/") ||
            v.contains("creativecommons.org/licenses/publicdomain/") -> LoopBoxRights.REUSE_ALLOWED
            v.isBlank() -> LoopBoxRights.UNKNOWN
            else -> LoopBoxRights.UNKNOWN
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun escapeArchive(value: String) = value.replace("\"", " ").replace(":", " ")
    private fun cleanHtml(value: String) = value.replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").trim()
}
