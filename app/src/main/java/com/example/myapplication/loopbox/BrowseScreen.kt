package com.example.myapplication.loopbox

import android.app.DownloadManager
import android.content.Context
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.home.recommendations.DiscoveryCard
import com.example.myapplication.ui.home.recommendations.RecommendationsSheet
import com.example.myapplication.ui.home.recommendations.RecommendationsUiState
import com.example.myapplication.ui.home.recommendations.RecommendationsViewModel
import com.example.myapplication.ui.home.recommendations.getRecommendationsStrings
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.lazy.rememberLazyListState
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
    var searching by BrowseSearchBus::active
    DisposableEffect(Unit) { onDispose { BrowseSearchBus.active = false } }
    val recsViewModel: RecommendationsViewModel = koinViewModel()
    val recsState by recsViewModel.uiState.collectAsStateWithLifecycle()
    val recsStrings = getRecommendationsStrings(AppLanguage.EN)
    val genreRepository: GenreRepository = koinInject()
    var showRecs by remember { mutableStateOf(false) }
    val animeRepository: AnimeRepository = koinInject()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var selected by remember { mutableStateOf<BrowseItem?>(null) }
    var videoUrls by remember { mutableStateOf<List<String>?>(null) }
    var readerUrl by remember { mutableStateOf<String?>(null) }
    var novel by remember { mutableStateOf<BrowseItem?>(null) }
    val modePrefs = remember { context.getSharedPreferences("loopbox_browse", Context.MODE_PRIVATE) }
    var downloadMode by remember { mutableStateOf(modePrefs.getString("mode", "stream") == "download") }
    val music = remember { MusicController(context) }
    DisposableEffect(music) { onDispose { music.release() } }

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
            // Both engines: Browse's free sources first, then the app's own progressive catalogue search.
            val own = BrowseRepository.search(tab, query)
            results = own
            val type = when (tab) {
                BrowseTab.Movies -> AppContentType.MOVIE
                BrowseTab.TV -> AppContentType.SERIES
                else -> null
            }
            if (type != null) {
                try {
                    animeRepository.searchApiProgressive(query.trim(), type, AppLanguage.EN).collect { list ->
                        val extra = list.mapNotNull { r -> apiResultToItem(r, tab) }
                        results = (own + extra).distinctBy { it.title.lowercase() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The free-source results stay on screen.
                }
            }
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
                            if (downloadMode) {
                                tracks.forEachIndexed { i, t ->
                                    val ext = t.second.substringAfterLast('.', "mp3").take(4)
                                    val name = t.first.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifBlank { "track ${i + 1}" } + "." + ext
                                    enqueueDownload(context, t.second, name, android.os.Environment.DIRECTORY_MUSIC)
                                }
                                Toast.makeText(context, "Downloading ${tracks.size} track(s)", Toast.LENGTH_SHORT).show()
                            } else {
                                val items = tracks.mapIndexed { i, t -> item.copy(id = item.id + "#" + i, title = t.first, subtitle = item.title) }
                                music.play(items, tracks.map { it.second })
                            }
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
            val recsReady = recsState as? RecommendationsUiState.Ready
            if (recsReady != null && recsReady.items.isNotEmpty() && !searching) {
                item(key = "discovery_card") {
                    DiscoveryCard(
                        state = recsReady,
                        strings = recsStrings,
                        onClick = { showRecs = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (tab != BrowseTab.Novels) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Free files:", color = Muted, fontSize = 13.sp)
                        OutlinedButton(
                            onClick = {
                                downloadMode = !downloadMode
                                modePrefs.edit().putString("mode", if (downloadMode) "download" else "stream").apply()
                            },
                            border = BorderStroke(1.dp, Color(0xFF3A3A3C)),
                        ) { Text(if (downloadMode) "Download" else "Stream", color = Fg) }
                    }
                }
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
                item {
                    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
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
            onRead = { b -> if (b.textUrl != null) novel = b else readerUrl = b.readUrl },
            downloadMode = downloadMode,
            onModeChange = { downloadMode = it; modePrefs.edit().putString("mode", if (it) "download" else "stream").apply() },
            onOpenInLibrary = { id -> selected = null; onOpenInLibrary(id) },
        )
    }
    videoUrls?.let { urls -> VideoDialog(urls) { videoUrls = null } }
    readerUrl?.let { url -> ReaderDialog(url) { readerUrl = null } }
    novel?.let { b -> NovelReader(b) { novel = null } }
    if (showRecs) {
        RecommendationsSheet(
            state = recsState,
            strings = recsStrings,
            language = AppLanguage.EN,
            genreRepository = genreRepository,
            onAdd = { item -> recsViewModel.addToCollection(item) },
            onRate = { item, verdict -> recsViewModel.rate(item, verdict) },
            onUndoRate = { item -> recsViewModel.undoRate(item) },
            onLoadMore = { recsViewModel.loadMore() },
            onDismiss = {
                showRecs = false
                recsViewModel.onSheetClosed()
            },
        )
    }
}

private fun apiResultToItem(r: ApiSearchResult, tab: BrowseTab): BrowseItem? {
    if (r.title.isBlank()) return null
    return BrowseItem(
        id = "api" + (r.externalId ?: r.title),
        tab = tab,
        title = r.title,
        subtitle = r.genres.take(2).joinToString(", "),
        imageUrl = r.posterUrl,
        description = r.description,
    )
}

private fun enqueueDownload(context: Context, url: String, fileName: String, dir: String) {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(
        DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, dir, fileName),
    )
}

/** The app's own text reader: fetches the book, shows it page-less with adjustable size, remembers the place. */
@Composable
private fun NovelReader(item: BrowseItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("loopbox_reader", Context.MODE_PRIVATE) }
    var paragraphs by remember(item.id) { mutableStateOf<List<String>?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(prefs.getInt("font", 18)) }
    val listState = rememberLazyListState()
    var restored by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        try {
            paragraphs = BrowseRepository.bookParagraphs(item.textUrl!!).ifEmpty { null }
            failed = paragraphs == null
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(paragraphs) {
        val p = paragraphs ?: return@LaunchedEffect
        val saved = prefs.getInt("pos_" + item.id, 0).coerceIn(0, (p.size - 1).coerceAtLeast(0))
        listState.scrollToItem(saved)
        restored = true
    }
    LaunchedEffect(listState.firstVisibleItemIndex, restored) {
        if (restored) prefs.edit().putInt("pos_" + item.id, listState.firstVisibleItemIndex).apply()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF111111)).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { fontSize = (fontSize - 2).coerceAtLeast(12); prefs.edit().putInt("font", fontSize).apply() }) { Text("A−", color = Fg) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { fontSize = (fontSize + 2).coerceAtMost(32); prefs.edit().putInt("font", fontSize).apply() }) { Text("A+", color = Fg) }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            }
            val p = paragraphs
            when {
                failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Couldn't load this book. Check your connection.", color = Muted)
                }
                p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(p.size) { i ->
                        Text(p[i], color = Color(0xFFDDDDDD), fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp)
                    }
                }
            }
        }
    }
}

/** Reads a book inside the app: Project Gutenberg text, or an Internet Archive / Open Library page. */
@Composable
private fun ReaderDialog(url: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            AndroidView(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        setBackgroundColor(android.graphics.Color.parseColor("#111111"))
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                                if (u != null && u.contains("gutenberg.org")) {
                                    view?.evaluateJavascript(
                                        "document.body.style.cssText='background:#111;color:#ddd;max-width:42em;margin:0 auto;padding:16px;font-size:18px;line-height:1.6';",
                                        null,
                                    )
                                }
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
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
    onRead: (BrowseItem) -> Unit,
    downloadMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onOpenInLibrary: (String) -> Unit,
) {
    val context = LocalContext.current
    val local: AnimeLocalDataSource = koinInject()
    val images: ImageStorageRepository = koinInject()
    var fullParts by remember(item.id) { mutableStateOf<List<String>?>(null) }
    var lookingUp by remember(item.id) { mutableStateOf(item.tab == BrowseTab.Movies || item.tab == BrowseTab.TV) }
    var libraryId by remember(item.id) { mutableStateOf<String?>(null) }
    val isVideo = item.tab == BrowseTab.Movies || item.tab == BrowseTab.TV

    LaunchedEffect(item.id) {
        if (!isVideo) return@LaunchedEffect
        try {
            val ia = BrowseRepository.findFreeVideo(item)
            if (ia != null) fullParts = BrowseRepository.videoParts(ia).ifEmpty { null }
        } catch (e: Exception) {
            fullParts = null
        }
        lookingUp = false
    }
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
                val movie = item.tab == BrowseTab.Movies
                val epCount = if (movie) 1 else (BrowseRepository.tvEpisodeCount(item.title) ?: 1)
                local.insertAnime(
                    Anime(
                        id = id,
                        title = item.title,
                        episodes = epCount,
                        rating = 0f,
                        imageFileName = file,
                        orderIndex = 0,
                        dateAdded = System.currentTimeMillis(),
                        categoryType = if (movie) "MOVIE" else "SERIES",
                        mediaType = if (movie) MediaType.MOVIE else MediaType.SERIES,
                        releaseYear = item.year.toIntOrNull(),
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Mode", color = Muted, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { onModeChange(!downloadMode) },
                        border = BorderStroke(1.dp, Color(0xFF3A3A3C)),
                    ) { Text(if (downloadMode) "Download" else "Stream", color = Fg) }
                }
                when {
                    lookingUp -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                        Text("Looking for a free full version…", color = Muted, fontSize = 13.sp)
                    }
                    fullParts != null && !downloadMode -> Button(
                        onClick = { onPlay(fullParts!!) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("▶ Play full" + if (fullParts!!.size > 1) " (${fullParts!!.size} parts)" else "", fontWeight = FontWeight.Bold) }
                    fullParts != null -> Button(
                        onClick = {
                            try {
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                fullParts!!.forEachIndexed { i, url ->
                                    val name = item.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifBlank { "video" } +
                                        (if (fullParts!!.size > 1) " part ${i + 1}" else "") + ".mp4"
                                    val req = DownloadManager.Request(Uri.parse(url))
                                        .setTitle(name)
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, name)
                                    dm.enqueue(req)
                                }
                                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not start the download", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("⬇ Download full", fontWeight = FontWeight.Bold) }
                    else -> Text("No free full version found. Use your Library page to open it with your sources.", color = Muted, fontSize = 13.sp)
                }
                if (item.previewUrl != null) {
                    OutlinedButton(
                        onClick = { onPlay(listOf(item.previewUrl)) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFF3A3A3C)),
                    ) { Text("Watch trailer", color = Fg) }
                }
                if (libraryId != null) {
                    OutlinedButton(
                        onClick = { onOpenInLibrary(libraryId!!) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFF3A3A3C)),
                    ) { Text("Open in Library", color = Fg) }
                }
            }
            if (item.tab == BrowseTab.Novels && item.readUrl != null) {
                Button(
                    onClick = { onRead(item) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("📖 Read", fontWeight = FontWeight.Bold) }
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
