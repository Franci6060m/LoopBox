package com.example.myapplication.mediacatalog

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

enum class DiscoveryKind { MOVIE, SERIES, ANIME, MUSIC }

/** A metadata hit and, separately, any file that the publisher explicitly makes reusable. */
data class DiscoveryHit(
    val title: String,
    val creator: String? = null,
    val year: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
    val metadataUrl: String? = null,
    val source: String,
    val files: List<PermittedMediaFile> = emptyList(),
)

data class PermittedMediaFile(
    val name: String,
    val url: String,
    val format: String?,
    val sizeBytes: Long?,
    val qualityLabel: String?,
    val licenseUrl: String,
    val canStream: Boolean,
    val canDownload: Boolean,
)

/**
 * Federated discovery for music and openly licensed/public-domain media.
 *
 * Metadata providers never imply a media download right. Internet Archive file URLs are
 * exposed only when the item's declared license is a Creative Commons or public-domain
 * license. DRM services and sources that do not grant file access are metadata-only.
 */
class MediaDiscoverySearch(
    http: OkHttpClient,
    private val internetArchiveBase: String = "https://archive.org",
    private val itunesBase: String = "https://itunes.apple.com",
    private val musicBrainzBase: String = "https://musicbrainz.org/ws/2",
) {
    private val client = http.newBuilder()
        .callTimeout(18, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Curated first-screen catalogue feeds; metadata/artwork only unless a separately licensed file is found. */
    suspend fun featured(kind: DiscoveryKind, limit: Int = 20): List<DiscoveryHit> = withContext(Dispatchers.IO) {
        val count = limit.coerceIn(1, 50)
        try {
            when (kind) {
                DiscoveryKind.ANIME -> searchJikanTop(count)
                DiscoveryKind.SERIES -> searchTvMazeTop(count)
                DiscoveryKind.MUSIC -> searchItunesTop(count)
                DiscoveryKind.MOVIE -> (searchCinemeta("movie", null, count) + searchArchiveFeatured("movies", count)).distinctBy { it.title.lowercase() }.take(count)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }

    private fun searchTvMazeTop(limit: Int): List<DiscoveryHit> {
        val root = getJson("https://api.tvmaze.com/shows?page=0") as? JsonArray ?: return emptyList()
        return root.take(limit).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val image = o["image"]?.jsonObjectOrNull()
            DiscoveryHit(o.string("name") ?: return@mapNotNull null, year = o.string("premiered")?.take(4),
                description = o.string("summary")?.replace(Regex("<[^>]*>"), "")?.take(400),
                artworkUrl = image?.string("original") ?: image?.string("medium"), metadataUrl = o.string("url"), source = "TVMaze")
        }
    }

    private fun searchJikanTop(limit: Int): List<DiscoveryHit> {
        val root = getJson("https://api.jikan.moe/v4/top/anime?limit=$limit") as? JsonObject ?: return emptyList()
        return root["data"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val jpg = o["images"]?.jsonObjectOrNull()?.get("jpg")?.jsonObjectOrNull()
            DiscoveryHit(o.string("title_english") ?: o.string("title") ?: return@mapNotNull null,
                year = o.string("year"), description = o.string("synopsis")?.take(400),
                artworkUrl = jpg?.string("large_image_url") ?: jpg?.string("image_url"),
                metadataUrl = o["mal_id"]?.jsonPrimitive?.contentOrNull?.let { "https://myanimelist.net/anime/" + it }, source = "MyAnimeList via Jikan")
        }
    }

    private fun searchItunesTop(limit: Int): List<DiscoveryHit> {
        val root = getJson("https://itunes.apple.com/us/rss/topsongs/limit=$limit/explicit=true/json") as? JsonObject ?: return emptyList()
        return root["feed"]?.jsonObjectOrNull()?.get("entry")?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = o["im:name"]?.jsonObjectOrNull()?.get("label")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artist = o["im:artist"]?.jsonObjectOrNull()?.get("label")?.jsonPrimitive?.contentOrNull
            val image = o["im:image"]?.jsonArray?.lastOrNull()?.jsonObjectOrNull()?.get("label")?.jsonPrimitive?.contentOrNull
            val link = o["link"]?.jsonArray?.firstOrNull()?.jsonObjectOrNull()?.get("attributes")?.jsonObjectOrNull()?.get("href")?.jsonPrimitive?.contentOrNull
            DiscoveryHit(title, creator = artist, artworkUrl = image, metadataUrl = link, source = "Apple iTunes")
        }
    }

    private fun searchArchiveFeatured(mediaType: String, limit: Int): List<DiscoveryHit> {
        val url = "$internetArchiveBase/advancedsearch.php".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", "mediatype:$mediaType AND (licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*)")
            ?.addQueryParameter("fl[]", "identifier,title,creator,year,description,licenseurl")
            ?.addQueryParameter("rows", limit.toString())?.addQueryParameter("sort[]", "downloads desc")
            ?.addQueryParameter("output", "json")?.build() ?: return emptyList()
        val root = getJson(url.toString()) as? JsonObject ?: return emptyList()
        val docs = root["response"]?.jsonObjectOrNull()?.get("docs")?.jsonArray ?: return emptyList()
        return docs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.string("identifier") ?: return@mapNotNull null
            val license = o.string("licenseurl")
            DiscoveryHit(o.string("title") ?: return@mapNotNull null, creator = o.string("creator"), year = o.string("year"),
                description = o.string("description"), metadataUrl = "$internetArchiveBase/details/$id", source = "Internet Archive",
                files = if (isReusableLicense(license)) loadArchiveFiles(id, license!!) else emptyList())
        }
    }
    suspend fun search(query: String, kind: DiscoveryKind, limit: Int = 25): List<DiscoveryHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val count = limit.coerceIn(1, 50)
        return withContext(Dispatchers.IO) {
            try {
                val results = coroutineScope {
                    when (kind) {
                        DiscoveryKind.MUSIC -> listOf(
                            async { searchItunes(q, count) },
                            async { searchMusicBrainz(q, count) },
                            async { searchArchive(q, "audio", count) },
                        ).map { it.await() }
                        DiscoveryKind.MOVIE -> listOf(
                            async { searchCinemeta("movie", q, count) },
                            async { searchArchive(q, "movies", count) },
                        ).map { it.await() }
                        DiscoveryKind.SERIES -> listOf(
                            async { searchCinemeta("series", q, count) },
                            async { searchTvMaze(q, count) },
                            async { searchArchive(q, "movies", count) },
                        ).map { it.await() }
                        DiscoveryKind.ANIME -> listOf(
                            async { searchJikan(q, count) },
                        ).map { it.await() }
                    }
                }
                deduplicate(results.flatten()).take(count)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Cinemeta metadata catalogue supplies current/popular movie and series posters. */
    private fun searchCinemeta(kind: String, query: String?, limit: Int): List<DiscoveryHit> {
        val url = "https://v3-cinemeta.strem.io/catalog/$kind/top.json".toHttpUrlOrNull()?.newBuilder()
            ?.apply { if (!query.isNullOrBlank()) addQueryParameter("search", query) }
            ?.build() ?: return emptyList()
        val root = getJson(url.toString()) as? JsonObject ?: return emptyList()
        return root["metas"]?.jsonArray.orEmpty().take(limit).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            DiscoveryHit(
                title = o.string("name") ?: return@mapNotNull null,
                year = o.string("releaseInfo")?.take(4),
                description = o.string("description")?.take(500),
                artworkUrl = o.string("poster"),
                metadataUrl = o.string("imdb_id")?.let { "https://www.imdb.com/title/$it/" },
                source = "Cinemeta",
            )
        }
    }

    /** Public TVMaze catalogue metadata and poster artwork for TV series. */
    private fun searchTvMaze(query: String, limit: Int): List<DiscoveryHit> {
        val url = "https://api.tvmaze.com/search/shows".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)?.build() ?: return emptyList()
        val results = getJson(url.toString()) as? JsonArray ?: return emptyList()
        return results.take(limit).mapNotNull { element ->
            val show = (element as? JsonObject)?.get("show")?.jsonObjectOrNull() ?: return@mapNotNull null
            val title = show.string("name") ?: return@mapNotNull null
            val image = show["image"]?.jsonObjectOrNull()
            DiscoveryHit(
                title = title,
                creator = show["network"]?.jsonObjectOrNull()?.string("name"),
                year = show.string("premiered")?.take(4),
                description = show.string("summary")?.replace(Regex("<[^>]*>"), "")?.take(500),
                artworkUrl = image?.string("original") ?: image?.string("medium"),
                metadataUrl = show.string("url"),
                source = "TVMaze",
            )
        }
    }

    /** MyAnimeList metadata via Jikan; results are catalogue entries, not downloadable media. */
    private fun searchJikan(query: String, limit: Int): List<DiscoveryHit> {
        val url = "https://api.jikan.moe/v4/anime".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("limit", limit.toString())
            ?.addQueryParameter("order_by", "popularity")
            ?.build() ?: return emptyList()
        val root = getJson(url.toString()) as? JsonObject ?: return emptyList()
        return root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val title = o.string("title_english") ?: o.string("title") ?: return@mapNotNull null
            val jpg = o["images"]?.jsonObjectOrNull()?.get("jpg")?.jsonObjectOrNull()
            DiscoveryHit(
                title = title,
                year = o.string("year"),
                description = o.string("synopsis")?.take(500),
                artworkUrl = jpg?.string("large_image_url") ?: jpg?.string("image_url"),
                metadataUrl = o["mal_id"]?.jsonPrimitive?.contentOrNull?.let { "https://myanimelist.net/anime/" + it },
                source = "MyAnimeList via Jikan",
            )
        }
    }
    private fun searchItunes(query: String, limit: Int): List<DiscoveryHit> {
        val url = "$itunesBase/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("term", query)
            ?.addQueryParameter("entity", "song")
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: return emptyList()
        val root = getJson(url.toString()) as? JsonObject ?: return emptyList()
        return root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val title = o.string("trackName") ?: return@mapNotNull null
            DiscoveryHit(
                title = title,
                creator = o.string("artistName"),
                year = o.string("releaseDate")?.take(4),
                description = o.string("collectionName"),
                artworkUrl = o.string("artworkUrl100")?.replace("100x100bb", "600x600bb"),
                metadataUrl = o.string("trackViewUrl"),
                source = "Apple iTunes",
            )
        }
    }

    private fun searchMusicBrainz(query: String, limit: Int): List<DiscoveryHit> {
        val url = "$musicBrainzBase/recording/".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("query", query)
            ?.addQueryParameter("fmt", "json")
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: return emptyList()
        val root = getJson(url.toString()) as? JsonObject ?: return emptyList()
        return root["recordings"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val title = o.string("title") ?: return@mapNotNull null
            val artist = o["artist-credit"]?.jsonArray?.firstOrNull()
                ?.jsonObjectOrNull()?.get("name")?.jsonPrimitive?.contentOrNull
            DiscoveryHit(
                title = title,
                creator = artist,
                description = o.string("disambiguation"),
                metadataUrl = o.string("id")?.let { "https://musicbrainz.org/recording/$it" },
                source = "MusicBrainz",
            )
        }
    }

    private fun searchArchive(query: String, mediaType: String, limit: Int): List<DiscoveryHit> {
        val url = "$internetArchiveBase/advancedsearch.php".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", "(\"title:$query\" OR \"creator:$query\") AND mediatype:$mediaType")
            ?.addQueryParameter("fl[]", "identifier,title,creator,year,description,licenseurl")
            ?.addQueryParameter("rows", limit.toString())
            ?.addQueryParameter("page", "1")
            ?.addQueryParameter("output", "json")
            ?.build() ?: return emptyList()
        val root = getJson(url.toString()) as? JsonObject ?: return emptyList()
        val docs = root["response"]?.jsonObjectOrNull()?.get("docs")?.jsonArray ?: return emptyList()
        return docs.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.string("identifier") ?: return@mapNotNull null
            val license = o.string("licenseurl")
            val files = if (isReusableLicense(license)) loadArchiveFiles(id, license!!) else emptyList()
            DiscoveryHit(
                title = o.string("title") ?: return@mapNotNull null,
                creator = o.string("creator"),
                year = o.string("year"),
                description = o.string("description"),
                metadataUrl = "$internetArchiveBase/details/$id",
                source = "Internet Archive",
                files = files,
            )
        }
    }

    private fun loadArchiveFiles(identifier: String, license: String): List<PermittedMediaFile> {
        val root = getJson("$internetArchiveBase/metadata/$identifier") as? JsonObject ?: return emptyList()
        val files = root["files"]?.jsonArray ?: return emptyList()
        return files.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = o.string("name") ?: return@mapNotNull null
            val format = o.string("format")
            val lower = name.lowercase()
            val allowedFormat = lower.endsWith(".mp3") || lower.endsWith(".ogg") ||
                lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                lower.endsWith(".webm") || lower.endsWith(".ogv")
            if (!allowedFormat || o.string("private") == "true") return@mapNotNull null
            val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20")
            val size = o.string("size")?.toLongOrNull()
            val url = "$internetArchiveBase/download/$identifier/$encoded"
            PermittedMediaFile(
                name = name,
                url = url,
                format = format ?: name.substringAfterLast('.', "").uppercase().ifBlank { null },
                sizeBytes = size,
                qualityLabel = qualityFromName(name),
                licenseUrl = license,
                canStream = true,
                canDownload = true,
            )
        }
    }

    private fun getJson(url: String): kotlinx.serialization.json.JsonElement? = try {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "LoopBox/1.0 (media discovery; contact: support@loopbox.app)")
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string().let(json::parseToJsonElement)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun isReusableLicense(value: String?): Boolean {
        val license = value?.lowercase()?.trim().orEmpty()
        return license.contains("creativecommons.org/publicdomain/") ||
            license.contains("creativecommons.org/licenses/by/") ||
            license.contains("creativecommons.org/licenses/by-sa/") ||
            license.contains("creativecommons.org/licenses/zero/")
    }

    private fun qualityFromName(name: String): String? {
        val n = name.lowercase()
        return when {
            "2160" in n || "4k" in n -> "4K"
            "1080" in n -> "1080p"
            "720" in n -> "720p"
            "480" in n -> "480p"
            "320" in n -> "320 kbps"
            "flac" in n -> "FLAC"
            else -> null
        }
    }

    private fun deduplicate(items: List<DiscoveryHit>): List<DiscoveryHit> {
        val seen = HashSet<String>()
        return items.filter { hit ->
            val key = "${hit.title.lowercase()}|${hit.creator.orEmpty().lowercase()}"
            seen.add(key)
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonElement?.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject
}
