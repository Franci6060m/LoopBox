package com.example.myapplication.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.myapplication.mediacatalog.DiscoveryHit
import com.example.myapplication.mediacatalog.DiscoveryKind
import com.example.myapplication.mediacatalog.MediaDiscoverySearch
import kotlinx.coroutines.launch

@Composable
internal fun MediaDiscoveryHomeFeed(search: MediaDiscoverySearch, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val feeds = remember { mutableStateMapOf<DiscoveryKind, List<DiscoveryHit>>() }
    val loading = remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<DiscoveryHit?>(null) }
    var selectedKind by remember { mutableStateOf(DiscoveryKind.MOVIE) }
    var lookupBusy by remember { mutableStateOf(false) }
    var lookupResults by remember { mutableStateOf<List<DiscoveryHit>>(emptyList()) }

    LaunchedEffect(search) {
        loading.value = true
        DiscoveryKind.entries.forEach { kind ->
            feeds[kind] = search.featured(kind, 16)
        }
        loading.value = false
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Discover something new", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (loading.value && feeds.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        listOf(
            DiscoveryKind.MOVIE to "Movies",
            DiscoveryKind.ANIME to "Anime",
            DiscoveryKind.SERIES to "TV series",
            DiscoveryKind.MUSIC to "Music",
        ).forEach { (kind, label) ->
            val items = feeds[kind].orEmpty()
            if (items.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.forEach { hit ->
                            Card(
                                modifier = Modifier.width(132.dp).clickable {
                                    selected = hit
                                    selectedKind = kind
                                    lookupResults = emptyList()
                                    lookupBusy = true
                                    scope.launch {
                                        lookupResults = search.search(hit.title, kind, 20)
                                        lookupBusy = false
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column {
                                    AsyncImage(
                                        model = hit.artworkUrl,
                                        contentDescription = hit.title,
                                        modifier = Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Text(hit.title, modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelLarge, maxLines = 2, minLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { hit ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(hit.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hit.description?.take(320) ?: "Searching public catalogues for available sources.")
                    if (lookupBusy) CircularProgressIndicator()
                    val files = lookupResults.flatMap { it.files }.distinctBy { it.url }
                    if (files.isEmpty() && !lookupBusy) {
                        Text("No openly licensed playable files were found. Metadata links are shown below; subscription or DRM streams are not extracted.")
                    }
                    files.forEach { file ->
                        TextButton(onClick = { uriHandler.openUri(file.url) }) {
                            Text((if (file.canStream) "Open / stream" else "View") + " · " + file.name.take(55))
                        }
                    }
                    lookupResults.mapNotNull { it.metadataUrl }.distinct().take(4).forEach { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text("Open catalogue source") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}
