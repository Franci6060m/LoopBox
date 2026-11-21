package com.example.myapplication.loopbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.repository.ImageStorageRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.UUID

private val Bg = LoopBoxColors.Background
private val CardBg = LoopBoxColors.Card
private val Fg = LoopBoxColors.Foreground
private val Muted = LoopBoxColors.Muted
private val Accent = LoopBoxColors.Accent

/** Audio queue shared by the Browse page and its mini player. */
private class MusicController(context: Context) {
    val exo: ExoPlayer = ExoPlayer.Builder(context).build()
    var queue by mutableStateOf<List<BrowseItem>>(emptyList())
    var current by mutableStateOf<BrowseItem?>(null)
    var isPlaying by mutableStateOf(false)

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@MusicController.isPlaying = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = queue.getOrNull(exo.currentMediaItemIndex)
            }
        })
    }

    fun play(items: List<BrowseItem>, urls: List<String>, start: Int = 0) {
        if (items.isEmpty() || items.size != urls.size) return
        queue = items
        exo.setMediaItems(urls.map { MediaItem.fromUri(it) })
        exo.seekTo(start, 0L)
        exo.prepare()
        exo.play()
        current = items.getOrNull(start)
    }

    fun toggle() {
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun next() {
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
    }

    fun previous() {
        if (exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem() else exo.seekTo(0L)
    }

    fun release() {
        exo.release()
    }
}

@Composable
fun BrowseScreen(
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    onOpenInLibrary: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = BrowseTab.entries[tabIndex.coerceIn(0, BrowseTab.entries.size - 1)]
    val feeds = remember { mutableStateMapOf<BrowseTab, BrowseFeed>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var selected by remember { mutableStateOf<BrowseItem?>(null) }
    var videoUrls by remember { mutableStateOf<List<String>?>(null) }
    val music = remember { MusicController(context) }
    DisposableEffect(music) { onDispose { music.release() } }
    val tmdbSource: com.example.myapplication.network.tmdb.TmdbRemoteDataSource = koinInject()
    val anilistSource: com.example.myapplication.network.AniListRemoteDataSource = koinInject()
    val apiService: com.example.myapplication.network.ApiService = koinInject()
    val recEngine: com.example.myapplication.domain.recommendations.RecommendationEngine = koinInject()
    remember { BrowseRepository.bind(tmdbSource, anilistSource, apiService, recEngine) }

    LaunchedEffect(tab, reload) {
        if (feeds[tab] == null) {
            loading = true
            error = null
            try {
                feeds[tab] = BrowseRepository.feed(tab)
            } catch (e: Exception) {
                error = "Couldn't reach the catalog. Check your connection."
            }
            loading = false
        }
    }
    LaunchedEffect(query, tab) {
        if (query.trim().length < 2) {
            results = emptyList()
        } else {
            delay(400)
            results = BrowseRepository.search(tab, query)
        }
    }

    fun openItem(item: BrowseItem, siblings: List<BrowseItem>) {
        if (item.tab == BrowseTab.Music) {
            scope.launch {
                try {
                    if (item.iaId != null) {
                        val tracks = BrowseRepository.audioTracks(item.iaId)
                        if (tracks.isEmpty()) {
                            Toast.makeText(context, "No playable file found", Toast.LENGTH_SHORT).show()
                        } else {
                            val items = tracks.mapIndexed { i, t -> item.copy(id = item.id + "#" + i, title = t.first, subtitle = item.title) }
                            music.play(items, tracks.map { it.second })
                        }
                    } else {
                        val playable = siblings.filter { it.previewUrl != null }
                        val start = playable.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        music.play(playable, playable.map { it.previewUrl!! }, start)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Couldn't play this track", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            selected = item
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomInset + 96.dp),
        ) {
            item {
                TabBar(
                    tabIndex = tabIndex,
                    onTab = { tabIndex = it; query = "" },
                    onSearch = { searching = !searching; if (!searching) query = "" },
                )
            }
            if (searching) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search ${tab.label.lowercase()}", color = Muted) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            val feed = feeds[tab]
            if (searching && query.trim().length >= 2) {
                item { SectionTitle("Results") }
                if (results.isEmpty()) {
                    item { Text("Nothing found yet", color = Muted, modifier = Modifier.padding(20.dp)) }
                } else {
                    val rows = results.chunked(3)
                    items(rows.size) { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rows[r].forEach { it2 ->
                                Box(Modifier.weight(1f)) { PosterCard(it2, Modifier.fillMaxWidth()) { openItem(it2, results) } }
                            }
                            repeat(3 - rows[r].size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else if (loading && feed == null) {
                item { BrowseSkeleton(Modifier.padding(top = 8.dp)) }
            } else if (feed == null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(error ?: "Nothing to show", color = Muted)
                        Button(onClick = { reload++ }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Retry") }
                    }
                }
            } else {
                if (feed.hero.isNotEmpty()) {
                    item { Hero(feed.hero) { openItem(it, feed.hero) } }
                }
                if (feed.charts.isNotEmpty()) {
                    item { Charts(if (tab == BrowseTab.Music) "Top charts" else if (tab == BrowseTab.Novels) "Popular" else "Rankings", feed.charts) { it2, list -> openItem(it2, list) } }
                }
                feed.rows.forEach { row ->
                    item(key = tab.name + row.title) {
                        SectionTitle(row.title)
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(row.items, key = { _, it2 -> it2.id }) { _, it2 ->
                                PosterCard(it2, Modifier.width(130.dp)) { openItem(it2, row.items) }
                            }
                        }
                    }
                }
            }
        }

        music.current?.let { cur ->
            MiniPlayer(
                item = cur,
                playing = music.isPlaying,
                onToggle = { music.toggle() },
                onNext = { music.next() },
                onPrev = { music.previous() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp, start = 12.dp, end = 12.dp),
            )
        }
    }

    selected?.let { item ->
        DetailSheet(
            item = item,
            onDismiss = { selected = null },
            onPlay = { urls -> videoUrls = urls },
            onListen = { novel ->
                scope.launch {
                    try {
                        Toast.makeText(context, "Looking for a narrated version…", Toast.LENGTH_SHORT).show()
                        val id = BrowseRepository.findAudiobook(novel)
                        val tracks = if (id == null) emptyList() else BrowseRepository.audioTracks(id)
                        if (tracks.isEmpty()) {
                            Toast.makeText(context, "No narrated version of this novel yet", Toast.LENGTH_LONG).show()
                        } else {
                            selected = null
                            val items = tracks.mapIndexed { i, t -> novel.copy(id = novel.id + "#" + i, tab = BrowseTab.Music, title = t.first, subtitle = novel.title) }
                            music.play(items, tracks.map { it.second })
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Couldn't load the narration", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onOpenInLibrary = { id -> selected = null; onOpenInLibrary(id) },
        )
    }
    videoUrls?.let { urls -> VideoDialog(urls) { videoUrls = null } }
}

@Composable
private fun TabBar(tabIndex: Int, onTab: (Int) -> Unit, onSearch: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            BrowseTab.entries.forEachIndexed { i, t ->
                Column(Modifier.clickable { onTab(i) }) {
                    Text(
                        t.label,
                        color = if (i == tabIndex) Color.White else Muted,
                        fontSize = 18.sp,
                        fontWeight = if (i == tabIndex) FontWeight.Black else FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.height(3.dp).width(if (i == tabIndex) 28.dp else 0.dp).clip(RoundedCornerShape(2.dp)).background(Accent),
                    )
                }
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search", tint = Fg) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun Hero(items: List<BrowseItem>, onOpen: (BrowseItem) -> Unit) {
    val pager = rememberPagerState(pageCount = { items.size })
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(item) }) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0x990A0A0A), Bg)),
                    ),
                )
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 34.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(item.subtitle, item.year).filter { it.isNotBlank() }.joinToString(" · "),
                            color = Fg,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Accent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Open", tint = Color.White) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier.height(6.dp).width(if (i == pager.currentPage) 22.dp else 6.dp)
                        .clip(CircleShape).background(if (i == pager.currentPage) Accent else Color(0xFF3A3A3C)),
                )
            }
        }
    }
}

@Composable
private fun Charts(title: String, charts: Map<String, List<BrowseItem>>, onOpen: (BrowseItem, List<BrowseItem>) -> Unit) {
    val names = charts.keys.toList()
    var selectedName by remember(names) { mutableStateOf(names.first()) }
    val list = charts[selectedName].orEmpty()
    SectionTitle(title)
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(names, key = { _, n -> n }) { _, n ->
            val on = n == selectedName
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0xFFF5F0E8) else Color(0xFF2A2A2C))
                    .clickable { selectedName = n }.padding(horizontal = 20.dp, vertical = 12.dp),
            ) { Text(n, color = if (on) Color(0xFF111111) else Fg, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
    Spacer(Modifier.height(14.dp))
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(list, key = { _, it2 -> it2.id }) { index, it2 ->
            PosterCard(it2, Modifier.width(140.dp), rank = index + 1) { onOpen(it2, list) }
        }
    }
}

@Composable
private fun PosterCard(item: BrowseItem, modifier: Modifier = Modifier, rank: Int? = null, onClick: () -> Unit) {
    val square = item.tab == BrowseTab.Music
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (square) 1f else 2f / 3f).clip(RoundedCornerShape(16.dp)).background(CardBg)) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (rank != null) {
                Text(
                    rank.toString(),
                    color = Color(0xCCF5F0E8),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOf(item.subtitle, item.year).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiniPlayer(
    item: BrowseItem,
    playing: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xF21C1C1E)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(CardBg),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPrev) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Fg) }
        IconButton(onClick = onToggle) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play or pause", tint = Accent)
        }
        IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Fg) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(
    item: BrowseItem,
    onDismiss: () -> Unit,
    onPlay: (List<String>) -> Unit,
    onListen: (BrowseItem) -> Unit = {},
    onOpenInLibrary: (String) -> Unit,
) {
    val context = LocalContext.current
    val local: AnimeLocalDataSource = koinInject()
    val images: ImageStorageRepository = koinInject()
    var libraryId by remember(item.id) { mutableStateOf<String?>(null) }
    val isVideo = item.tab == BrowseTab.Movies || item.tab == BrowseTab.TV || item.tab == BrowseTab.Anime

    // Opening a movie or show puts it into the Library, so nobody has to add titles by hand.
    LaunchedEffect(item.id) {
        if (!isVideo) return@LaunchedEffect
        try {
            val prefs = context.getSharedPreferences("loopbox_browse", Context.MODE_PRIVATE)
            val known = prefs.getString("lib_" + item.id, null)
            if (known != null) {
                libraryId = known
            } else {
                val id = UUID.randomUUID().toString()
                val file = item.imageUrl?.let { images.saveImageFromUrl(it, id).getOrNull() }
                val api = item.api
                val src = api?.source?.uppercase().orEmpty()
                val ext = api?.externalId?.toIntOrNull()
                val kind = when (item.tab) { BrowseTab.Movies -> MediaType.MOVIE; BrowseTab.Anime -> MediaType.ANIME; else -> MediaType.SERIES }
                val episodes = (api?.totalEpisodes ?: api?.airedEpisodes ?: api?.episodes ?: 0).coerceAtLeast(1)
                local.insertAnime(
                    Anime(
                        id = id,
                        title = item.title,
                        episodes = episodes,
                        rating = 0f,
                        imageFileName = file,
                        orderIndex = 0,
                        dateAdded = System.currentTimeMillis(),
                        categoryType = api?.categoryType?.takeIf { it.isNotBlank() } ?: kind.name,
                        mediaType = kind,
                        releaseYear = item.year.toIntOrNull(),
                        // Link to the real source so the app loads every season/episode itself.
                        tmdbId = item.tmdbId ?: if (src.contains("TMDB")) ext else null,
                        anilistId = if (src.contains("ANILIST")) ext else null,
                        shikimoriId = if (src.contains("SHIKIMORI")) ext else null,
                        malId = api?.malId ?: if (src == "MAL" || src == "JIKAN") ext else null,
                    ),
                )
                prefs.edit().putString("lib_" + item.id, id).apply()
                libraryId = id
            }
        } catch (e: Exception) {
            libraryId = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1E),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(CardBg),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(listOf(item.subtitle, item.year).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 14.sp)
                    if (libraryId != null) Text("Added to your Library", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (item.description.isNotBlank()) {
                Text(item.description, color = Fg, fontSize = 14.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
            }
            if (isVideo) {
                Button(
                    onClick = { libraryId?.let(onOpenInLibrary) },
                    enabled = libraryId != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text(if (libraryId == null) "Adding to Library…" else "▶ Watch · all seasons & episodes", fontWeight = FontWeight.Bold) }
            }
            if (item.tab == BrowseTab.Novels && item.readUrl != null) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.readUrl)))
                        } catch (e: Exception) {
                            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("📖 Read", fontWeight = FontWeight.Bold) }
            }
            if (item.tab == BrowseTab.Novels) {
                OutlinedButton(
                    onClick = { onListen(item) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color(0xFF3A3A3C)),
                ) { Text("🎧 Listen · human narrator", color = Fg) }
            }
        }
    }
}

@Composable
private fun VideoDialog(urls: List<String>, onClose: () -> Unit) {
    val context = LocalContext.current
    val exo = remember(urls) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(urls.map { MediaItem.fromUri(it) })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = exo; useController = true } },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
