package com.example.myapplication.loopbox

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
import java.util.Calendar
import com.example.myapplication.domain.recommendations.ContentType
import com.example.myapplication.domain.recommendations.RecommendationEngine
import com.example.myapplication.domain.recommendations.RecommendationItem
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.ApiService
import com.example.myapplication.network.AniListRemoteDataSource
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.tmdb.TmdbRemoteDataSource

/**
 * Catalog for the Browse page. Movies, TV and Anime use the app's own systems: TMDB / AniList lists,
 * the recommendation engine ("Picked for you") and the native search. Novels come from Open Library
 * (English only), music from Deezer, narrated novels from LibriVox on the Internet Archive.
 */
object BrowseRepository {
    private const val UA = "LoopBox/1.0 (Android)"
    private const val IA = "https://archive.org"
    private val videoFormats = listOf("h.264 hd", "h.264", "mpeg4", "512kb mpeg4")
    private val audioFormats = listOf("vbr mp3", "mp3", "128kbps mp3", "64kbps mp3", "ogg vorbis")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun json(url: String): JSONObject = JSONObject(get(url))

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun JSONObject.str(name: String): String = optString(name, "").let { if (it == "null") "" else it }

    // ---------------- Internet Archive ----------------

    private data class IaDoc(val id: String, val title: String, val year: String, val creator: String, val license: String)

    private fun JSONObject.first(name: String): String {
        val v = opt(name)
        return when (v) {
            is JSONArray -> if (v.length() > 0) v.optString(0, "") else ""
            null -> ""
            else -> v.toString()
        }
    }

    private suspend fun archive(query: String, rows: Int): List<IaDoc> {
        val url = "$IA/advancedsearch.php?q=${enc(query)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=creator&fl%5B%5D=licenseurl" +
            "&rows=$rows&sort%5B%5D=${enc("downloads desc")}&output=json"
        val docs = json(url).optJSONObject("response")?.optJSONArray("docs").objects()
        return docs.mapNotNull {
            val id = it.first("identifier")
            val title = it.first("title")
            if (id.isBlank() || title.isBlank()) null
            else IaDoc(id, title, it.first("year"), it.first("creator"), it.first("licenseurl"))
        }
    }

    private fun isFreeVideo(d: IaDoc): Boolean {
        val lic = d.license.lowercase()
        if (lic.contains("publicdomain") || lic.contains("creativecommons.org/licenses")) return true
        val year = d.year.take(4).toIntOrNull() ?: return false
        return year < Calendar.getInstance().get(Calendar.YEAR) - 95
    }

    private fun isFreeAudio(d: IaDoc): Boolean {
        val lic = d.license.lowercase()
        return lic.contains("creativecommons") || lic.contains("publicdomain")
    }

    private fun IaDoc.toItem(tab: BrowseTab, group: String): BrowseItem = BrowseItem(
        id = "ia$id",
        tab = tab,
        title = title,
        subtitle = creator.ifBlank { group },
        year = year.take(4),
        imageUrl = "$IA/services/img/$id",
        description = "Free full-length " + when (tab) {
            BrowseTab.Music -> "track"
            BrowseTab.TV -> "episode"
            else -> "film"
        } + " from the Internet Archive.",
        iaId = id,
    )

    private suspend fun archiveRow(tab: BrowseTab, label: String, subject: String): BrowseRow? {
        val base = when (tab) {
            BrowseTab.Movies -> "collection:(feature_films) AND mediatype:(movies)"
            BrowseTab.TV -> "collection:(classic_tv) AND mediatype:(movies)"
            else -> "mediatype:(audio) AND collection:(opensource_audio OR netlabels)"
        }
        val q = if (subject.isBlank()) base else "$base AND subject:($subject)"
        val free: (IaDoc) -> Boolean = if (tab == BrowseTab.Music) ::isFreeAudio else ::isFreeVideo
        val items = archive(q, 60).filter(free).take(15).map { it.toItem(tab, label) }.distinctBy { it.id }
        return if (items.isEmpty()) null else BrowseRow(label, items)
    }

    /** Every playable (.mp4) part of an Internet Archive video item, best format first. */
    suspend fun videoParts(iaId: String): List<String> {
        val files = json("$IA/metadata/$iaId").optJSONArray("files").objects()
            .filter { it.str("name").lowercase().endsWith(".mp4") }
        val fmt = videoFormats.firstOrNull { f -> files.any { it.str("format").lowercase() == f } } ?: return emptyList()
        return files.filter { it.str("format").lowercase() == fmt }
            .map { it.str("name") }
            .sorted()
            .map { "$IA/download/$iaId/" + it.split("/").joinToString("/") { part -> enc(part).replace("+", "%20") } }
    }

    /** Tracks of an Internet Archive audio item as (title, url). */
    suspend fun audioTracks(iaId: String): List<Pair<String, String>> {
        val files = json("$IA/metadata/$iaId").optJSONArray("files").objects()
            .filter { val n = it.str("name").lowercase(); n.endsWith(".mp3") || n.endsWith(".ogg") }
        val fmt = audioFormats.firstOrNull { f -> files.any { it.str("format").lowercase() == f } } ?: return emptyList()
        return files.filter { it.str("format").lowercase() == fmt }
            .sortedBy { it.str("name") }
            .take(50)
            .map {
                val name = it.str("name")
                val title = it.str("title").ifBlank { name.substringAfterLast('/').substringBeforeLast('.') }
                title to "$IA/download/$iaId/" + name.split("/").joinToString("/") { part -> enc(part).replace("+", "%20") }
            }
    }

    /** Looks for a free, full-length film for a title that came from iTunes. */
    suspend fun findFreeVideo(item: BrowseItem): String? {
        if (item.iaId != null) return item.iaId
        val clean = item.title.replace(Regex(",?\\s*(season|book|series)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()
        val words = clean.replace(Regex("[()\":\\[\\]{}]"), " ").trim()
        if (words.isBlank()) return null
        val col = if (item.tab == BrowseTab.TV) "classic_tv" else "feature_films"
        val docs = archive("collection:($col) AND mediatype:(movies) AND title:($words)", 10)
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return docs.firstOrNull { norm(it.title) == norm(clean) && isFreeVideo(it) }?.id
    }

    // ---------------- iTunes ----------------

    private suspend fun itunes(term: String, tab: BrowseTab, limit: Int = 20): List<BrowseItem> {
        val movie = tab == BrowseTab.Movies
        val entity = if (movie) "movie" else "tvSeason"
        val media = if (movie) "movie" else "tvShow"
        val results = json("https://itunes.apple.com/search?term=${enc(term)}&media=$media&entity=$entity&limit=$limit")
            .optJSONArray("results").objects()
        return results.mapNotNull { r ->
            val title = (if (movie) r.str("trackName") else r.str("collectionName"))
            val id = (if (movie) r.optLong("trackId") else r.optLong("collectionId")).toString()
            if (title.isBlank() || id == "0") return@mapNotNull null
            BrowseItem(
                id = "it$id",
                tab = tab,
                title = title,
                subtitle = r.str("primaryGenreName"),
                year = r.str("releaseDate").take(4),
                imageUrl = r.str("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100bb", "600x600bb"),
                description = r.str("longDescription").ifBlank { r.str("shortDescription") },
                previewUrl = r.str("previewUrl").takeIf { it.startsWith("http") },
            )
        }.distinctBy { it.id }
    }

    // ---------------- Open Library ----------------

    private suspend fun novels(query: String, limit: Int = 20): List<BrowseItem> {
        val url = "https://openlibrary.org/search.json?q=${enc(query)}&has_fulltext=true&public_scan_b=true&language=eng&sort=rating&limit=$limit" +
            "&fields=key,title,author_name,cover_i,first_publish_year,ia"
        return json(url).optJSONArray("docs").objects().mapNotNull { d ->
            val title = d.str("title")
            if (title.isBlank()) return@mapNotNull null
            val author = d.optJSONArray("author_name")?.optString(0, "").orEmpty()
            val cover = d.optLong("cover_i", 0L)
            val ia = d.optJSONArray("ia")?.optString(0, "").orEmpty()
            BrowseItem(
                id = "ol" + d.str("key"),
                tab = BrowseTab.Novels,
                title = title,
                subtitle = author,
                year = d.str("first_publish_year"),
                imageUrl = if (cover > 0) "https://covers.openlibrary.org/b/id/$cover-M.jpg" else null,
                description = "Free to read online from the Internet Archive and Open Library.",
                readUrl = if (ia.isNotBlank()) "$IA/details/$ia" else "https://openlibrary.org" + d.str("key"),
            )
        }.distinctBy { it.id }
    }

    // ---------------- Deezer ----------------

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

    private suspend fun deezerChart(id: Int): List<BrowseItem> =
        deezerItems(json("https://api.deezer.com/chart/$id/tracks?limit=20").optJSONArray("data"))

    // ---------------- Native systems (TMDB / AniList / recommendation engine / app search) ----------------

    private var tmdb: TmdbRemoteDataSource? = null
    private var anilist: AniListRemoteDataSource? = null
    private var api: ApiService? = null
    private var engine: RecommendationEngine? = null

    /** Called once by the screen with the app's own (Koin) services. */
    fun bind(tmdb: TmdbRemoteDataSource, anilist: AniListRemoteDataSource, api: ApiService, engine: RecommendationEngine) {
        this.tmdb = tmdb
        this.anilist = anilist
        this.api = api
        this.engine = engine
    }

    private fun <T> LookupResult<List<T>>.listOrEmpty(): List<T> = if (this is LookupResult.Found) value else emptyList()

    private fun appType(tab: BrowseTab) = when (tab) {
        BrowseTab.Movies -> AppContentType.MOVIE
        BrowseTab.TV -> AppContentType.SERIES
        else -> AppContentType.ANIME
    }

    private fun ApiSearchResult.toItem(tab: BrowseTab, group: String): BrowseItem = BrowseItem(
        id = "api" + tab.name + (externalId ?: title.lowercase()),
        tab = tab,
        title = titleEn?.takeIf { it.isNotBlank() } ?: title,
        subtitle = genres.firstOrNull() ?: group,
        year = seasonYear?.toString().orEmpty(),
        imageUrl = posterUrl?.takeIf { it.isNotBlank() },
        description = description,
        api = this,
    )

    private fun RecommendationItem.toItem(tab: BrowseTab): BrowseItem = BrowseItem(
        id = "rec" + tab.name + key,
        tab = tab,
        title = title,
        subtitle = genres.firstOrNull().orEmpty(),
        imageUrl = coverUrl.takeIf { it.isNotBlank() },
        description = description,
        tmdbId = tmdbId,
    )

    private suspend fun pickedForYou(tab: BrowseTab): List<BrowseItem> {
        val want = when (tab) { BrowseTab.Movies -> ContentType.MOVIE; BrowseTab.TV -> ContentType.TV; else -> ContentType.ANIME }
        val e = engine ?: return emptyList()
        val snap = safe { e.refresh(AppLanguage.EN) } ?: safe { e.cached() } ?: return emptyList()
        return snap.items.filter { it.contentType == want }.map { it.toItem(tab) }.filter { it.imageUrl != null }.distinctBy { it.title }.take(20)
    }

    private fun yearsAgo(n: Int): String = "${Calendar.getInstance().get(Calendar.YEAR) - n}-01-01"

    private val movieGenres = listOf("Action" to 28, "Comedy" to 35, "Sci-Fi" to 878, "Horror" to 27, "Drama" to 18, "Thriller" to 53, "Animation" to 16)
    private val tvGenres = listOf("Drama" to 18, "Comedy" to 35, "Sci-Fi & Fantasy" to 10765, "Crime" to 80, "Action & Adventure" to 10759, "Animation" to 16, "Reality" to 10764)

    private suspend fun screenFeed(tab: BrowseTab): BrowseFeed = coroutineScope {
        val isTv = tab == BrowseTab.TV
        val t = tmdb
        val mine = async { pickedForYou(tab) }
        if (t != null && t.hasApiKey) {
            val trending = async { safe { t.trending(isTv, AppLanguage.EN).listOrEmpty().map { it.toItem(tab, "Trending") } } ?: emptyList() }
            val fresh = async {
                safe { t.discover(isTv, emptyList(), AppLanguage.EN, minVotes = 150, sortBy = "popularity.desc", since = yearsAgo(1)).listOrEmpty().map { it.toItem(tab, "New") } } ?: emptyList()
            }
            val top = async {
                safe { t.discover(isTv, emptyList(), AppLanguage.EN, minVotes = 2000, sortBy = "vote_average.desc", since = yearsAgo(15)).listOrEmpty().map { it.toItem(tab, "Top rated") } } ?: emptyList()
            }
            val genres = (if (isTv) tvGenres else movieGenres).map { (n, id) ->
                async {
                    n to (safe { t.discover(isTv, listOf(id), AppLanguage.EN, minVotes = 200, sortBy = "popularity.desc", since = yearsAgo(3)).listOrEmpty().map { it.toItem(tab, n) } } ?: emptyList())
                }
            }
            val trendingItems = trending.await()
            val charts = genres.awaitAll().filter { it.second.isNotEmpty() }.toMap()
            val rows = buildList {
                mine.await().takeIf { it.isNotEmpty() }?.let { add(BrowseRow("Picked for you", it)) }
                fresh.await().takeIf { it.isNotEmpty() }?.let { add(BrowseRow("New & popular", it)) }
                top.await().takeIf { it.isNotEmpty() }?.let { add(BrowseRow("Critically acclaimed", it)) }
            }
            BrowseFeed(trendingItems.filter { it.imageUrl != null }.take(8), charts, rows)
        } else {
            // No TMDB key in this build: the recommendation engine (Trakt / TasteDive / AniList sources) still fills the page.
            val items = mine.await()
            val byGenre = items.groupBy { it.subtitle.ifBlank { "More" } }.filterValues { it.size >= 3 }
            BrowseFeed(items.take(8), byGenre, if (items.isEmpty()) emptyList() else listOf(BrowseRow("Picked for you", items)))
        }
    }

    private suspend fun animeFeed(): BrowseFeed = coroutineScope {
        val al = anilist
        val mine = async { pickedForYou(BrowseTab.Anime) }
        val trending = async {
            if (al == null) emptyList() else (safe { al.trendingAnime(40).getOrNull() } ?: emptyList()).map { it.toItem(BrowseTab.Anime, "Trending") }
        }
        val all = trending.await()
        val byGenre = all.groupBy { it.subtitle.ifBlank { "More" } }.filterValues { it.size >= 3 }
        val rows = buildList {
            mine.await().takeIf { it.isNotEmpty() }?.let { add(BrowseRow("Picked for you", it)) }
            if (all.isNotEmpty()) add(BrowseRow("Trending now", all))
        }
        BrowseFeed(all.filter { it.imageUrl != null }.take(8), byGenre, rows)
    }

    // ---------------- Feed + search ----------------

    private suspend fun <T> safe(block: suspend () -> T?): T? = try { block() } catch (e: Exception) { null }

    suspend fun feed(tab: BrowseTab): BrowseFeed = coroutineScope {
        when (tab) {
            BrowseTab.Movies, BrowseTab.TV -> screenFeed(tab)
            BrowseTab.Anime -> animeFeed()
            BrowseTab.Novels -> {
                val subjects = listOf("Fantasy", "Mystery", "Romance", "Science fiction", "History", "Adventure", "Horror")
                val jobs = subjects.map { s -> async { s to (safe { novels("subject:\"${s.lowercase()}\"") } ?: emptyList()) } }
                val all = jobs.awaitAll().filter { it.second.isNotEmpty() }
                BrowseFeed(all.firstOrNull()?.second.orEmpty().filter { it.imageUrl != null }.take(8), all.toMap(), emptyList())
            }
            BrowseTab.Music -> {
                val genres = listOf("Pop" to 132, "Hip-Hop" to 116, "Rock" to 152, "R&B" to 165, "Dance" to 113, "Jazz" to 129)
                val chartJobs = genres.map { (n, id) -> async { n to (safe { deezerChart(id) } ?: emptyList()) } }
                val trending = safe { deezerChart(0) } ?: emptyList()
                val charts = chartJobs.awaitAll().filter { it.second.isNotEmpty() }.toMap()
                BrowseFeed(trending.filter { it.imageUrl != null }.take(8), charts, if (trending.isEmpty()) emptyList() else listOf(BrowseRow("Trending now", trending)))
            }
        }
    }

    suspend fun search(tab: BrowseTab, query: String): List<BrowseItem> = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope emptyList()
        when (tab) {
            // Same search as the app's bottom search bar.
            BrowseTab.Movies, BrowseTab.TV, BrowseTab.Anime -> {
                val a = api ?: return@coroutineScope emptyList()
                (safe { a.searchApi(q, appType(tab), AppLanguage.EN).getOrNull() } ?: emptyList())
                    .map { it.toItem(tab, "Result") }.distinctBy { it.id }
            }
            BrowseTab.Novels -> safe { novels(q, 30) } ?: emptyList()
            BrowseTab.Music -> safe { deezerItems(json("https://api.deezer.com/search?q=${enc(q)}&limit=30").optJSONArray("data")) } ?: emptyList()
        }
    }

    // ---------------- Narrated novels (LibriVox on the Internet Archive) ----------------

    /** Identifier of a human-narrated (LibriVox) recording of this novel, if one exists. */
    suspend fun findAudiobook(item: BrowseItem): String? {
        val clean = item.title.replace(Regex("[()\":\\[\\]{}]"), " ").trim()
        if (clean.isBlank()) return null
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val docs = archive("collection:(librivoxaudio) AND mediatype:(audio) AND title:($clean)", 10)
        return docs.firstOrNull { norm(it.title).startsWith(norm(item.title)) }?.id
    }
}
