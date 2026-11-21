package com.example.myapplication.loopbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.TextButton
import com.example.myapplication.mediacatalog.LoopBoxHomeDiscoveryRepository
import com.example.myapplication.mediacatalog.LoopBoxMediaDownloadService
import com.example.myapplication.mediacatalog.LoopBoxMediaFile
import com.example.myapplication.mediacatalog.LoopBoxMediaFileChecker
import com.example.myapplication.mediacatalog.LoopBoxMediaItem
import com.example.myapplication.mediacatalog.LoopBoxMediaKind
import com.example.myapplication.mediacatalog.LoopBoxMediaScraper
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
import com.example.myapplication.domain.recommendations.RecommendationEngine
import com.example.myapplication.domain.search.AddFromApiUseCase
import com.example.myapplication.mediacatalog.CloudMediaSearch
import com.example.myapplication.network.ApiService
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.loopbox.BrowseRepository.toBrowse
import kotlinx.coroutines.flow.first
import org.koin.compose.getKoin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.PaddingValues
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
    val koin = getKoin()
    val scope = rememberCoroutineScope()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = BrowseTab.entries[tabIndex.coerceIn(0, BrowseTab.entries.size - 1)]
    val feeds = remember { mutableStateMapOf<BrowseTab, BrowseFeed>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var watchChip by rememberSaveable { mutableStateOf("All") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchKind by rememberSaveable { mutableStateOf("Anime") }
    var results by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<BrowseItem?>(null) }
    val music = remember { MusicController(context) }
    val discovery = remember { LoopBoxHomeDiscoveryRepository(LoopBoxMediaScraper()) }
    val checker = remember { LoopBoxMediaFileChecker() }
    val downloader = remember { LoopBoxMediaDownloadService() }
    val extra = remember { mutableStateMapOf<BrowseTab, List<BrowseRow>>() }
    var homeSections by remember { mutableStateOf<Map<LoopBoxMediaKind, List<LoopBoxMediaItem>>?>(null) }
    var resolving by remember { mutableStateOf<String?>(null) }
    var video by remember { mutableStateOf<VideoSession?>(null) }
    DisposableEffect(music) { onDispose { music.release() } }

    LaunchedEffect(tab, reload) {
        if (feeds[tab] == null) {
            loading = true
            error = null
            try {
                feeds[tab] = when (tab) {
                    BrowseTab.Watch -> BrowseRepository.watchFeed(koin.get<RecommendationEngine>(), koin.get<ApiService>())
                    BrowseTab.Novels -> BrowseRepository.novelsFeed()
                    BrowseTab.Music -> BrowseRepository.musicFeed()
                }
            } catch (e: Exception) {
                error = "Couldn't reach the catalog. Check your connection."
            }
            loading = false
        }
    }

    // Full songs and films found by the LoopBox media scraper (files verified before they are shown).
    LaunchedEffect(tab) {
        if (tab == BrowseTab.Novels || extra[tab] != null) return@LaunchedEffect
        val sections: Map<LoopBoxMediaKind, List<LoopBoxMediaItem>> = homeSections
            ?: try { discovery.refreshHome().also { homeSections = it } } catch (e: Exception) { emptyMap() }
        fun row(title: String, kind: LoopBoxMediaKind): BrowseRow? =
            sections[kind].orEmpty().filter { it.files.isNotEmpty() }.map { it.toBrowse(tab) }
                .takeIf { it.isNotEmpty() }?.let { BrowseRow(title, it) }
        extra[tab] = if (tab == BrowseTab.Music) {
            listOfNotNull(row("Free full songs", LoopBoxMediaKind.MUSIC))
        } else {
            listOfNotNull(row("Free full movies", LoopBoxMediaKind.MOVIE), row("Free full TV series", LoopBoxMediaKind.SERIES))
        }
    }

    LaunchedEffect(query, tab, searchKind) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searchBusy = false
            return@LaunchedEffect
        }
        delay(400)
        searchBusy = true
        results = emptyList()
        try {
            when (tab) {
                BrowseTab.Novels -> results = BrowseRepository.searchNovels(q)
                BrowseTab.Music -> {
                    val own = try {
                        discovery.search(q, LoopBoxMediaKind.MUSIC).filter { it.files.isNotEmpty() }.map { it.toBrowse(BrowseTab.Music) }
                    } catch (e: Exception) { emptyList() }
                    results = own
                    results = (own + BrowseRepository.searchMusic(q)).distinctBy { it.id }
                }
                BrowseTab.Watch -> {
                    val type = when (searchKind) {
                        "Movies" -> AppContentType.MOVIE
                        "TV Series" -> AppContentType.SERIES
                        else -> AppContentType.ANIME
                    }
                    val ownKind = when (searchKind) {
                        "Movies" -> LoopBoxMediaKind.MOVIE
                        "TV Series" -> LoopBoxMediaKind.SERIES
                        else -> null
                    }
                    val own = if (ownKind == null) emptyList() else try {
                        discovery.search(q, ownKind).filter { it.files.isNotEmpty() }.map { it.toBrowse(BrowseTab.Watch) }
                    } catch (e: Exception) { emptyList() }
                    if (own.isNotEmpty()) results = own
                    val cloudSearch = koin.getOrNull<CloudMediaSearch>()
                    val cloud = try { cloudSearch?.search(q, type, AppLanguage.EN).orEmpty() } catch (e: Exception) { emptyList() }
                    if (cloud.isNotEmpty()) results = own + cloud.map { it.toBrowse() }
                    try {
                        koin.get<AnimeRepository>().searchApiProgressive(q, type, AppLanguage.EN).collect { list ->
                            if (list.isNotEmpty()) results = (own + CloudMediaSearch.mergeCloudFirst(cloud, list).map { it.toBrowse() }).distinctBy { it.id }
                        }
                    } catch (e: Exception) {
                        // keep whatever arrived
                    }
                }
            }
        } catch (e: Exception) {
            // empty results are shown below
        }
        searchBusy = false
    }

    /** Opening any title adds it to the collection first (no manual "add"), then shows its page. */
    fun openWatch(item: BrowseItem) {
        val api = item.api ?: return
        if (opening) return
        opening = true
        scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                koin.get<AddFromApiUseCase>().invoke(api)
                val all = koin.get<AnimeLocalDataSource>().observeAllAnime().first()
                val match = all.firstOrNull { it.title.equals(api.title, ignoreCase = true) }
                    ?: all.filter { it.dateAdded >= t0 - 2000 }.maxByOrNull { it.dateAdded }
                if (match != null) onOpenInLibrary(match.id)
                else Toast.makeText(context, "Couldn't open this title", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't open this title", Toast.LENGTH_SHORT).show()
            }
            opening = false
        }
    }

    fun playFullVideo(item: BrowseItem) {
        scope.launch {
            resolving = "Finding the full video…"
            try {
                val files = bestFileGroup(item.files, video = true)
                val first = checker.check(files.first())
                if (first.playable) {
                    video = VideoSession(item.title, listOf(first.finalUrl) + files.drop(1).map { it.url }, files)
                } else {
                    Toast.makeText(context, first.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't open the video", Toast.LENGTH_SHORT).show()
            }
            resolving = null
        }
    }

    fun playFullAudio(item: BrowseItem) {
        scope.launch {
            resolving = "Finding the full song…"
            try {
                val files = if (item.file != null) listOf(item.file) else bestFileGroup(item.files, video = false)
                val first = checker.check(files.first())
                if (first.playable) {
                    val tracks = files.mapIndexed { i, f ->
                        BrowseItem(id = item.id + "#" + i, tab = BrowseTab.Music, title = fileTitle(f), subtitle = item.title, imageUrl = item.imageUrl, file = f)
                    }
                    music.play(tracks, listOf(first.finalUrl) + files.drop(1).map { it.url })
                } else {
                    Toast.makeText(context, first.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't play this song", Toast.LENGTH_SHORT).show()
            }
            resolving = null
        }
    }

    fun download(files: List<LoopBoxMediaFile>, folder: String) {
        scope.launch {
            try {
                val saved = downloadFiles(context, files, folder, checker, downloader) { i, n ->
                    Toast.makeText(context, "Downloading $i of $n…", Toast.LENGTH_SHORT).show()
                }
                Toast.makeText(
                    context,
                    if (saved.isEmpty()) "Nothing could be downloaded" else "Saved to " + saved.first().parentFile?.path,
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Download failed: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openItem(item: BrowseItem, siblings: List<BrowseItem>) {
        when (item.tab) {
            BrowseTab.Watch -> if (item.files.isNotEmpty()) playFullVideo(item) else openWatch(item)
            BrowseTab.Novels -> reading = item
            BrowseTab.Music -> if (item.files.isNotEmpty() || item.file != null) playFullAudio(item) else {
                val playable = siblings.filter { it.previewUrl != null }
                val start = playable.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                if (playable.isNotEmpty()) music.play(playable, playable.map { it.previewUrl!! }, start)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 96.dp),
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
                if (tab == BrowseTab.Watch) {
                    item { ChipRow(listOf("Anime", "Movies", "TV Series"), searchKind) { searchKind = it } }
                }
            } else if (tab == BrowseTab.Watch) {
                item { ChipRow(listOf("All", "Anime", "Movies", "TV Series"), watchChip) { watchChip = it } }
            }
            val feed = feeds[tab]
            if (searching && query.trim().length >= 2) {
                item { SectionTitle(if (searchBusy && results.isEmpty()) "Searching…" else "Results") }
                if (results.isEmpty() && !searchBusy) {
                    item { Text("Nothing found", color = Muted, modifier = Modifier.padding(20.dp)) }
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
            } else if (feed == null && (loading || error == null)) {
                item { FeedSkeleton() }
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
                val chip = if (tab == BrowseTab.Watch) watchChip else "All"
                val rows = (extra[tab].orEmpty() + feed.rows)
                    .map { r -> if (chip == "All") r else r.copy(items = r.items.filter { it.kind == chip }) }
                    .filter { it.items.isNotEmpty() }
                val hero = if (chip == "All") feed.hero else rows.flatMap { it.items }.filter { it.imageUrl != null }.distinctBy { it.id }.take(8)
                if (hero.isNotEmpty()) {
                    item(key = "hero_" + tab.name + chip) { Hero(hero) { openItem(it, hero) } }
                }
                if (rows.isEmpty()) {
                    item { Text("Nothing here yet", color = Muted, modifier = Modifier.padding(20.dp)) }
                }
                rows.forEach { row ->
                    item(key = tab.name + chip + row.title) {
                        SectionTitle(row.title)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(row.items, key = { _, it2 -> it2.id }) { index, it2 ->
                                PosterCard(it2, Modifier.width(132.dp), rank = if (row.title.startsWith("Top") || row.title.startsWith("Popular")) index + 1 else null) { openItem(it2, row.items) }
                            }
                        }
                    }
                }
            }
        }

        resolving?.let { msg -> ResolvingOverlay(msg) }
        if (opening) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        }
        music.current?.let { cur ->
            MiniPlayer(
                item = cur,
                playing = music.isPlaying,
                onToggle = { music.toggle() },
                onNext = { music.next() },
                onPrev = { music.previous() },
                onDownload = cur.file?.let { f -> { download(listOf(f), "Music") } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp, start = 12.dp, end = 12.dp),
            )
        }
    }

    reading?.let { book -> BookReaderDialog(book) { reading = null } }
    video?.let { v -> VideoDialog(v, onClose = { video = null }, onDownload = { download(v.files, "Movies") }) }
}

@Composable
private fun ChipRow(names: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(names, key = { _, n -> n }) { _, n ->
            val on = n == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0xFFF5F0E8) else Color(0xFF2A2A2C))
                    .clickable { onSelect(n) }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text(n, color = if (on) Color(0xFF111111) else Fg, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    return Brush.linearGradient(
        colors = listOf(Color(0xFF161618), Color(0xFF262629), Color(0xFF161618)),
        start = Offset(x - 400f, 0f),
        end = Offset(x, 0f),
    )
}

/** Placeholder shown while a feed loads: the hero and two rows of posters. */
@Composable
private fun FeedSkeleton() {
    val brush = shimmerBrush()
    Column {
        Box(Modifier.fillMaxWidth().height(400.dp).background(brush))
        repeat(2) {
            Spacer(Modifier.height(22.dp))
            Box(Modifier.padding(start = 20.dp).width(150.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Box(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(brush))
                }
            }
        }
    }
}

@Composable
private fun TabBar(tabIndex: Int, onTab: (Int) -> Unit, onSearch: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
                            item.subtitle,
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
        val sub = item.subtitle
        if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class VideoSession(val title: String, val urls: List<String>, val files: List<LoopBoxMediaFile>)

/** Loading skeleton shown while the scraper's file is being verified. */
@Composable
private fun ResolvingOverlay(message: String) {
    val brush = shimmerBrush()
    Box(Modifier.fillMaxSize().background(Color(0xE60A0A0A)).clickable { }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(brush))
            Box(Modifier.fillMaxWidth(0.6f).height(18.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            Box(Modifier.fillMaxWidth(0.4f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            Text(message, color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun VideoDialog(session: VideoSession, onClose: () -> Unit, onDownload: () -> Unit) {
    val context = LocalContext.current
    val exo = remember(session) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(session.urls.map { MediaItem.fromUri(it) })
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
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                Text(session.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onDownload) { Text("Download", color = Accent, fontWeight = FontWeight.Bold) }
            }
        }
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
    onDownload: (() -> Unit)? = null,
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
        if (onDownload != null) {
            IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download", tint = Fg) }
        }
        IconButton(onClick = onPrev) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Fg) }
        IconButton(onClick = onToggle) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play or pause", tint = Accent)
        }
        IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Fg) }
    }
}

