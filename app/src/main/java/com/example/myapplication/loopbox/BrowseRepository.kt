package com.example.myapplication.loopbox

import com.example.myapplication.domain.recommendations.ContentType
import com.example.myapplication.domain.recommendations.RecommendationEngine
import com.example.myapplication.domain.recommendations.RecommendationItem
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.ApiService
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.home.recommendations.toApiSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Feeds for the home page.
 *  - Watch: the app's own recommendation engine and AniList trending (the same systems behind
 *    "Watch Recommended"), plus current Apple charts when TMDB is not configured.
 *  - Novels: English books from Project Gutenberg (Gutendex).
 *  - Music: current Deezer charts (30-second previews).
 */
object BrowseRepository {
    private const val UA = "LoopBox/1.0 (Android)"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 25_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun fetchText(url: String): String = get(url)

    private suspend fun json(url: String): JSONObject = JSONObject(get(url))

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun JSONObject.str(name: String): String = optString(name, "").let { if (it == "null") "" else it }

    private suspend fun <T> safe(block: suspend () -> T?): T? = try { block() } catch (e: Exception) { null }

    // ---------------- Watch ----------------

    private fun RecommendationItem.toBrowse(): BrowseItem {
        val kind = when (contentType) {
            ContentType.ANIME -> "Anime"
            ContentType.MOVIE -> "Movies"
            ContentType.TV -> "TV Series"
        }
        return BrowseItem(
            id = "rc$key",
            tab = BrowseTab.Watch,
            title = title,
            subtitle = genres.take(2).joinToString(" · "),
            kind = kind,
            imageUrl = coverUrl.takeIf { it.isNotBlank() },
            description = description,
            api = toApiSearchResult(),
        )
    }

    fun ApiSearchResult.toBrowse(): BrowseItem {
        val kind = when (categoryType.uppercase()) {
            "MOVIE" -> "Movies"
            "SERIES", "TV", "TV_SERIES" -> "TV Series"
            "MANGA" -> "Manga"
            else -> "Anime"
        }
        return BrowseItem(
            id = "api" + source + (externalId ?: "") + title,
            tab = BrowseTab.Watch,
            title = title,
            subtitle = listOfNotNull(seasonYear?.toString(), genres.firstOrNull()).joinToString(" · "),
            kind = kind,
            imageUrl = posterUrl,
            description = description,
            api = this,
        )
    }

    private suspend fun itunesChart(feed: String, kind: String): List<BrowseItem> {
        val movie = kind == "Movies"
        val entries = json("https://itunes.apple.com/us/rss/$feed/limit=25/json")
            .optJSONObject("feed")?.optJSONArray("entry").objects()
        return entries.mapNotNull { e ->
            val name = e.optJSONObject("im:name")?.str("label").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val img = e.optJSONArray("im:image").objects().lastOrNull()?.str("label")
                ?.replace(Regex("\\d+x\\d+bb"), "600x600bb")
            val summary = e.optJSONObject("summary")?.str("label").orEmpty()
            val cat = e.optJSONObject("category")?.optJSONObject("attributes")?.str("label").orEmpty()
            val api = ApiSearchResult(
                title = name,
                altTitle = null,
                posterUrl = img,
                episodes = 1,
                description = summary,
                type = if (movie) "movie" else "tv",
                genres = if (cat.isBlank()) emptyList() else listOf(cat),
                rating = null,
                source = "itunes",
                categoryType = if (movie) "MOVIE" else "SERIES",
                externalId = null,
            )
            BrowseItem(
                id = "it$feed$name",
                tab = BrowseTab.Watch,
                title = name,
                subtitle = cat,
                kind = kind,
                imageUrl = img,
                description = summary,
                api = api,
            )
        }.distinctBy { it.id }
    }

    suspend fun watchFeed(engine: RecommendationEngine, api: ApiService): BrowseFeed = coroutineScope {
        val snapJob = async {
            try {
                engine.cached()?.takeIf { it.items.isNotEmpty() } ?: engine.refresh(AppLanguage.EN)
            } catch (e: Exception) {
                null
            }
        }
        val animeJob = async { try { api.anilistTrending(24).getOrNull().orEmpty() } catch (e: Exception) { emptyList() } }
        val movieJob = async { safe { itunesChart("topmovies", "Movies") }.orEmpty() }
        val tvJob = async { safe { itunesChart("toptvseasons", "TV Series") }.orEmpty() }

        val rec = snapJob.await()?.items.orEmpty().map { it.toBrowse() }.distinctBy { it.id }
        val anime = animeJob.await().map { it.toBrowse() }.distinctBy { it.id }
        val movies = (rec.filter { it.kind == "Movies" } + movieJob.await()).distinctBy { it.title }
        val shows = (rec.filter { it.kind == "TV Series" } + tvJob.await()).distinctBy { it.title }

        val rows = listOfNotNull(
            rec.takeIf { it.isNotEmpty() }?.let { BrowseRow("Recommended for you", it.take(24)) },
            anime.takeIf { it.isNotEmpty() }?.let { BrowseRow("Trending anime", it) },
            movies.takeIf { it.isNotEmpty() }?.let { BrowseRow("Popular movies", it) },
            shows.takeIf { it.isNotEmpty() }?.let { BrowseRow("Popular TV series", it) },
        )
        val hero = listOf(rec, anime, movies, shows)
            .flatMap { it.filter { x -> x.imageUrl != null }.take(2) }
            .distinctBy { it.id }
            .take(8)
        BrowseFeed(hero, rows)
    }

    // ---------------- Novels (English, readable in the app) ----------------

    private fun gutenbergItems(arr: JSONArray?): List<BrowseItem> = arr.objects().mapNotNull { b ->
        val formats = b.optJSONObject("formats") ?: return@mapNotNull null
        var text: String? = null
        val keys = formats.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("text/plain")) { text = formats.optString(k); break }
        }
        if (text.isNullOrBlank()) return@mapNotNull null
        val author = b.optJSONArray("authors").objects().firstOrNull()?.str("name").orEmpty()
            .split(",").map { it.trim() }.reversed().joinToString(" ")
        BrowseItem(
            id = "gb" + b.optInt("id"),
            tab = BrowseTab.Novels,
            title = b.str("title"),
            subtitle = author,
            imageUrl = formats.optString("image/jpeg").takeIf { it.startsWith("http") },
            description = b.optJSONArray("subjects")?.let { s -> (0 until minOf(s.length(), 3)).joinToString(" · ") { s.optString(it) } }.orEmpty(),
            textUrl = text,
        )
    }.distinctBy { it.id }

    private suspend fun gutenberg(query: String): List<BrowseItem> =
        gutenbergItems(json("https://gutendex.com/books/?languages=en&$query").optJSONArray("results"))

    suspend fun novelsFeed(): BrowseFeed = coroutineScope {
        val topics = listOf("Popular novels" to "", "Adventure" to "topic=adventure", "Mystery" to "topic=mystery", "Science fiction" to "topic=science%20fiction", "Romance" to "topic=love", "Fantasy" to "topic=fantasy")
        val jobs = topics.map { (n, q) -> async { n to (safe { gutenberg(q) } ?: emptyList()) } }
        val rows = jobs.awaitAll().filter { it.second.isNotEmpty() }.map { BrowseRow(it.first, it.second.take(24)) }
        BrowseFeed(rows.firstOrNull()?.items.orEmpty().filter { it.imageUrl != null }.take(8), rows)
    }

    suspend fun searchNovels(q: String): List<BrowseItem> =
        if (q.trim().length < 2) emptyList() else safe { gutenberg("search=" + enc(q.trim())) } ?: emptyList()

    // ---------------- Music ----------------

    private fun deezerItems(arr: JSONArray?): List<BrowseItem> = arr.objects().mapNotNull { o ->
        val preview = o.str("preview")
        if (!preview.startsWith("http")) return@mapNotNull null
        val album = o.optJSONObject("album")
        BrowseItem(
            id = "dz" + o.str("id"),
            tab = BrowseTab.Music,
            title = o.str("title"),
            subtitle = o.optJSONObject("artist")?.str("name").orEmpty(),
            imageUrl = album?.str("cover_big")?.takeIf { it.isNotBlank() },
            description = album?.str("title").orEmpty(),
            previewUrl = preview,
        )
    }.distinctBy { it.id }

    suspend fun musicFeed(): BrowseFeed = coroutineScope {
        val charts = listOf("Top tracks" to 0, "Pop" to 132, "Hip-Hop" to 116, "Rock" to 152, "R&B" to 165, "Dance" to 113, "Jazz" to 129)
        val jobs = charts.map { (n, id) ->
            async { n to (safe { deezerItems(json("https://api.deezer.com/chart/$id/tracks?limit=25").optJSONArray("data")) } ?: emptyList()) }
        }
        val rows = jobs.awaitAll().filter { it.second.isNotEmpty() }.map { BrowseRow(it.first, it.second) }
        BrowseFeed(rows.firstOrNull()?.items.orEmpty().filter { it.imageUrl != null }.take(8), rows)
    }

    suspend fun searchMusic(q: String): List<BrowseItem> =
        if (q.trim().length < 2) emptyList()
        else safe { deezerItems(json("https://api.deezer.com/search?q=${enc(q.trim())}&limit=30").optJSONArray("data")) } ?: emptyList()
}
