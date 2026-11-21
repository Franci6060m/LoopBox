package com.example.myapplication.loopbox

enum class BrowseTab(val label: String) {
    Movies("Movies"),
    TV("TV"),
    Anime("Anime"),
    Novels("Novels"),
    Music("Music"),
}

/** One poster/cover in the browse feed. */
data class BrowseItem(
    val id: String,
    val tab: BrowseTab,
    val title: String,
    val subtitle: String = "",
    val year: String = "",
    val imageUrl: String? = null,
    val description: String = "",
    /** Internet Archive identifier: a free, full-length file exists for this item. */
    val iaId: String? = null,
    /** 30-second preview or trailer (iTunes / Deezer). */
    val previewUrl: String? = null,
    /** Page where a novel can be read. */
    val readUrl: String? = null,
    /** Карточка нативного каталога (TMDB / AniList / ...): по ней тайтл попадает в Library со связкой источников. */
    val api: com.example.myapplication.network.ApiSearchResult? = null,
    /** TMDB id из рекомендаций движка (если карточка пришла оттуда). */
    val tmdbId: Int? = null,
)

data class BrowseRow(val title: String, val items: List<BrowseItem>)

data class BrowseFeed(
    val hero: List<BrowseItem>,
    /** Genre chips with their ranked lists (the "Rankings" / "Top charts" block). */
    val charts: Map<String, List<BrowseItem>>,
    val rows: List<BrowseRow>,
)
