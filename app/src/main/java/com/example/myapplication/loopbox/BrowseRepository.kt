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

/**
 * Catalog for the Browse page. Same public sources as the LoopBox web prototype:
 * iTunes (movie / TV posters and trailers), Open Library (novels), Deezer (song previews) and
 * the Internet Archive (free full-length films, TV and songs).
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
        val url = "https://openlibrary.org/search.json?q=${enc(query)}&has_fulltext=true&limit=$limit" +
            "&language=eng&fields=key,title,author_name,cover_i,first_publish_year,ia"
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

    // ---------------- Project Gutenberg (Gutendex) ----------------

    /** Public-domain English books. No key needed. */
    private suspend fun gutenberg(query: String, topic: String = ""): List<BrowseItem> {
        val q = if (query.isNotBlank()) "&search=${enc(query)}" else ""
        val t = if (topic.isNotBlank()) "&topic=${enc(topic)}" else ""
        val arr = json("https://gutendex.com/books/?languages=en&copyright=false$q$t").optJSONArray("results").objects()
        return arr.mapNotNull { b ->
            val title = b.str("title")
            val id = b.optInt("id", 0)
            if (title.isBlank() || id == 0) return@mapNotNull null
            val author = b.optJSONArray("authors").objects().firstOrNull()?.str("name").orEmpty()
            val formats = b.optJSONObject("formats")
            val read = formats?.str("text/html").orEmpty().ifBlank { "https://www.gutenberg.org/ebooks/$id" }
            BrowseItem(
                id = "pg$id",
                tab = BrowseTab.Novels,
                title = title,
                subtitle = author,
                imageUrl = formats?.str("image/jpeg")?.takeIf { it.startsWith("http") },
                description = "Public-domain English book from Project Gutenberg.",
                readUrl = read,
            )
        }.distinctBy { it.id }
    }

    // ---------------- TVmaze (no key) ----------------

    /** Real episode count of a series, so a show is not stored as a single episode. */
    suspend fun tvEpisodeCount(title: String): Int? {
        val clean = title.replace(Regex(",?\\s*(season|book|series)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()
        if (clean.isBlank()) return null
        return try {
            val show = json("https://api.tvmaze.com/singlesearch/shows?q=${enc(clean)}&embed=episodes")
            val n = show.optJSONObject("_embedded")?.optJSONArray("episodes")?.length() ?: 0
            if (n > 0) n else null
        } catch (e: Exception) {
            null
        }
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

    // ---------------- Feed + search ----------------

    private suspend fun <T> safe(block: suspend () -> T?): T? = try { block() } catch (e: Exception) { null }

    suspend fun feed(tab: BrowseTab): BrowseFeed = coroutineScope {
        when (tab) {
            BrowseTab.Movies, BrowseTab.TV -> {
                val genres = if (tab == BrowseTab.Movies) listOf("Action", "Comedy", "Horror", "Romance", "Sci-Fi", "Drama")
                else listOf("Drama", "Comedy", "Crime", "Sci-Fi", "Animation", "Thriller")
                val free = if (tab == BrowseTab.Movies) listOf("Free full movies" to "", "Free sci-fi" to "sci-fi OR science fiction", "Free horror" to "horror", "Free comedy" to "comedy")
                else listOf("Free full TV" to "", "Free comedy" to "comedy", "Free kids" to "cartoon")
                val chartJobs = genres.map { g -> async { g to (safe { itunes(g, tab) } ?: emptyList()) } }
                val freeJobs = free.map { (l, s) -> async { safe { archiveRow(tab, l, s) } } }
                val charts = chartJobs.awaitAll().filter { it.second.isNotEmpty() }.toMap()
                val rows = freeJobs.awaitAll().filterNotNull()
                val hero = (rows.firstOrNull()?.items.orEmpty() + charts.values.firstOrNull().orEmpty())
                    .filter { it.imageUrl != null }.take(8)
                BrowseFeed(hero, charts, rows)
            }
            BrowseTab.Novels -> {
                val subjects = listOf("Fantasy", "Mystery", "Romance", "Science fiction", "History", "Adventure")
                val jobs = subjects.map { s ->
                    async {
                        val ol = safe { novels("subject:\"${s.lowercase()}\"") } ?: emptyList()
                        val pg = safe { gutenberg("", s.lowercase()) } ?: emptyList()
                        s to (ol + pg).distinctBy { it.title.lowercase() }
                    }
                }
                val all = jobs.awaitAll().filter { it.second.isNotEmpty() }
                val charts = all.toMap()
                BrowseFeed(all.firstOrNull()?.second.orEmpty().filter { it.imageUrl != null }.take(8), charts, emptyList())
            }
            BrowseTab.Music -> {
                val genres = listOf("Pop" to 132, "Hip-Hop" to 116, "Rock" to 152, "R&B" to 165, "Dance" to 113, "Jazz" to 129)
                val free = listOf("Free full songs" to "", "Free rock" to "rock", "Free electronic" to "electronic", "Free jazz" to "jazz", "Free classical" to "classical", "Free ambient" to "ambient")
                val chartJobs = genres.map { (n, id) -> async { n to (safe { deezerChart(id) } ?: emptyList()) } }
                val freeJobs = free.map { (l, s) -> async { safe { archiveRow(BrowseTab.Music, l, s) } } }
                val charts = chartJobs.awaitAll().filter { it.second.isNotEmpty() }.toMap()
                val rows = freeJobs.awaitAll().filterNotNull()
                val hero = (rows.firstOrNull()?.items.orEmpty() + charts.values.firstOrNull().orEmpty())
                    .filter { it.imageUrl != null }.take(8)
                BrowseFeed(hero, charts, rows)
            }
        }
    }

    suspend fun search(tab: BrowseTab, query: String): List<BrowseItem> = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope emptyList()
        when (tab) {
            BrowseTab.Movies, BrowseTab.TV -> {
                val a = async { safe { itunes(q, tab, 30) } ?: emptyList() }
                val col = if (tab == BrowseTab.TV) "classic_tv" else "feature_films"
                val b = async {
                    safe { archive("collection:($col) AND mediatype:(movies) AND title:($q)", 20).filter(::isFreeVideo).map { it.toItem(tab, "Free") } } ?: emptyList()
                }
                (b.await() + a.await()).distinctBy { it.id }
            }
            BrowseTab.Novels -> {
                val a = async { safe { novels(q, 30) } ?: emptyList() }
                val b = async { safe { gutenberg(q) } ?: emptyList() }
                (a.await() + b.await()).distinctBy { it.title.lowercase() }
            }
            BrowseTab.Music -> {
                val a = async { safe { deezerItems(json("https://api.deezer.com/search?q=${enc(q)}&limit=30").optJSONArray("data")) } ?: emptyList() }
                val b = async {
                    safe { archive("mediatype:(audio) AND title:($q)", 30).filter(::isFreeAudio).map { it.toItem(BrowseTab.Music, "Free") } } ?: emptyList()
                }
                (b.await() + a.await()).distinctBy { it.id }
            }
        }
    }
}
