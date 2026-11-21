package com.example.myapplication.loopbox

import android.content.Context
import com.example.myapplication.mediacatalog.LoopBoxMediaDownloadService
import com.example.myapplication.mediacatalog.LoopBoxMediaFile
import com.example.myapplication.mediacatalog.LoopBoxMediaFileChecker
import com.example.myapplication.mediacatalog.LoopBoxMediaItem
import com.example.myapplication.mediacatalog.LoopBoxMediaKind
import java.io.File

/** Turns an item found by the LoopBox media scraper into a card. */
internal fun LoopBoxMediaItem.toBrowse(tab: BrowseTab): BrowseItem = BrowseItem(
    id = id,
    tab = tab,
    title = title,
    subtitle = listOfNotNull(year, source).joinToString(" \u00B7 "),
    kind = when (kind) {
        LoopBoxMediaKind.MOVIE -> "Movies"
        LoopBoxMediaKind.SERIES -> "TV Series"
        LoopBoxMediaKind.MUSIC -> ""
    },
    imageUrl = artworkUrl,
    description = description,
    files = files,
)

/** Keeps one format of an item (for example the .mp4 files) so a film or album is not queued twice. */
internal fun bestFileGroup(files: List<LoopBoxMediaFile>, video: Boolean): List<LoopBoxMediaFile> {
    val order = if (video) listOf("mp4", "m4v", "webm", "ogv", "mkv") else listOf("mp3", "m4a", "ogg", "flac", "wav")
    for (ext in order) {
        val group = files.filter { it.name.lowercase().substringAfterLast('.') == ext }
        if (group.isNotEmpty()) return group.sortedBy { it.name }
    }
    return files
}

internal fun fileTitle(f: LoopBoxMediaFile): String =
    f.name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')

/** Verifies each file with the scraper's checker, then downloads it into the app's media folder. */
internal suspend fun downloadFiles(
    context: Context,
    files: List<LoopBoxMediaFile>,
    folder: String,
    checker: LoopBoxMediaFileChecker,
    downloader: LoopBoxMediaDownloadService,
    onProgress: (Int, Int) -> Unit,
): List<File> {
    val dir = File(context.getExternalFilesDir(null), "LoopBox/$folder")
    val saved = ArrayList<File>()
    files.forEachIndexed { i, f ->
        onProgress(i + 1, files.size)
        val check = checker.check(f)
        if (check.downloadable) {
            val name = f.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")
            saved += downloader.download(f, check, File(dir, name))
        }
    }
    return saved
}
