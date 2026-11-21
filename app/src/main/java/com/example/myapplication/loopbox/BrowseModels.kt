package com.example.myapplication.loopbox

import com.example.myapplication.network.ApiSearchResult

enum class BrowseTab(val label: String) {
    Watch("Watch"),
    Novels("Novels"),
    Music("Music"),
}

/** One poster/cover in the browse feed. */
data class BrowseItem(
    val id: String,
    val tab: BrowseTab,
    val title: String,
    val subtitle: String = "",
    /** "Anime", "Movies" or "TV Series" for the Watch tab. */
    val kind: String = "",
    val imageUrl: String? = null,
    val description: String = "",
    /** 30-second preview (Deezer). */
    val previewUrl: String? = null,
    /** Plain-text book (Project Gutenberg). */
    val textUrl: String? = null,
    /** The app's own search model: lets the existing "add to collection" system take over. */
    val api: ApiSearchResult? = null,
)

data class BrowseRow(val title: String, val items: List<BrowseItem>)

data class BrowseFeed(
    val hero: List<BrowseItem>,
    val rows: List<BrowseRow>,
)
