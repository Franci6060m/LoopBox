package com.example.myapplication.loopbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.util.Locale

/** Splits a Project Gutenberg plain-text book into reading pages (about 1,400 characters each). */
internal fun paginateBook(raw: String): List<String> {
    var t = raw.replace("\r\n", "\n")
    val start = t.indexOf("*** START OF")
    if (start >= 0) {
        val nl = t.indexOf('\n', start)
        t = t.substring(if (nl >= 0) nl + 1 else start)
    }
    val end = t.indexOf("*** END OF")
    if (end >= 0) t = t.substring(0, end)
    val paragraphs = t.split(Regex("\n\\s*\n"))
        .map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }
        .filter { it.isNotEmpty() }
    val pages = ArrayList<String>()
    val sb = StringBuilder()
    for (p in paragraphs) {
        if (sb.isNotEmpty() && sb.length + p.length > 1400) {
            pages.add(sb.toString().trim())
            sb.setLength(0)
        }
        sb.append(p).append("\n\n")
    }
    if (sb.isNotBlank()) pages.add(sb.toString().trim())
    return pages
}

/** Read-aloud using the best English voice the phone has installed. */
private class Speaker(context: Context) {
    var ready by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var voiceLabel by mutableStateOf("")
    var onPageDone: () -> Unit = {}
    private var voices: List<Voice> = emptyList()
    private var voiceIndex = 0
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.language = Locale.US
                voices = (engine.voices ?: emptySet<Voice>())
                    .filter { it.locale.language == "en" }
                    .sortedWith(
                        compareByDescending<Voice> { it.quality }
                            .thenByDescending { it.locale.country == "US" || it.locale.country == "GB" }
                            .thenBy { it.isNetworkConnectionRequired },
                    )
                    .take(8)
                applyVoice()
                engine.setSpeechRate(0.95f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { onPageDone() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { speaking = false }
                    }
                })
                ready = true
            }
        }
    }

    private fun applyVoice() {
        val v = voices.getOrNull(voiceIndex) ?: return
        tts?.voice = v
        voiceLabel = "Voice ${voiceIndex + 1}/${voices.size}"
    }

    fun nextVoice() {
        if (voices.isEmpty()) return
        voiceIndex = (voiceIndex + 1) % voices.size
        applyVoice()
    }

    fun speak(text: String) {
        speaking = true
        tts?.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "page")
    }

    fun stop() {
        speaking = false
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

@Composable
fun BookReaderDialog(item: BrowseItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember(item.id) { mutableStateOf<List<String>?>(null) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableStateOf(19) }
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }

    LaunchedEffect(item.id) {
        try {
            val text = BrowseRepository.fetchText(item.textUrl.orEmpty())
            val p = paginateBook(text)
            if (p.isEmpty()) error = "This book has no readable text." else pages = p
        } catch (e: Exception) {
            error = "Couldn't load the book. Check your connection."
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0D0B))) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                    Text(
                        item.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { if (fontSize > 14) fontSize -= 1 }) { Text("A−", color = LoopBoxColors.Foreground) }
                    TextButton(onClick = { if (fontSize < 30) fontSize += 1 }) { Text("A+", color = LoopBoxColors.Foreground) }
                }
                val list = pages
                when {
                    error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error!!, color = LoopBoxColors.Muted, modifier = Modifier.padding(24.dp))
                    }
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LoopBoxColors.Accent)
                    }
                    else -> {
                        val prefs = remember { context.getSharedPreferences("loopbox_reader", Context.MODE_PRIVATE) }
                        val pager = rememberPagerState(
                            initialPage = prefs.getInt("page_" + item.id, 0).coerceIn(0, list.size - 1),
                            pageCount = { list.size },
                        )
                        speaker.onPageDone = {
                            scope.launch {
                                if (speaker.speaking && pager.currentPage < list.size - 1) {
                                    pager.animateScrollToPage(pager.currentPage + 1)
                                } else {
                                    speaker.stop()
                                }
                            }
                        }
                        LaunchedEffect(pager.settledPage) {
                            prefs.edit().putInt("page_" + item.id, pager.settledPage).apply()
                        }
                        LaunchedEffect(pager.settledPage, speaker.speaking) {
                            if (speaker.speaking) speaker.speak(list[pager.settledPage])
                        }
                        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                            Text(
                                list[page],
                                color = Color(0xFFEDE4D3),
                                fontFamily = FontFamily.Serif,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.65f).sp,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = speaker.ready,
                                onClick = { if (speaker.speaking) speaker.stop() else speaker.speak(list[pager.currentPage]) },
                            ) { Text(if (speaker.speaking) "⏸ Pause" else "🔊 Read aloud", color = LoopBoxColors.Accent, fontWeight = FontWeight.Bold) }
                            TextButton(enabled = speaker.ready, onClick = { speaker.nextVoice() }) {
                                Text(if (speaker.voiceLabel.isBlank()) "Voice" else speaker.voiceLabel, color = LoopBoxColors.Foreground)
                            }
                            Text("${pager.currentPage + 1} / ${list.size}", color = LoopBoxColors.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
