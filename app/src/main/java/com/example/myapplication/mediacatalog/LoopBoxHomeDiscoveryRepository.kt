package com.example.myapplication.mediacatalog

/**
 * Small repository wrapper for the Home screen. Fetches each category independently so one
 * unavailable catalogue does not prevent the other Home sections from appearing.
 */
class LoopBoxHomeDiscoveryRepository(
    private val scraper: LoopBoxMediaScraper
) {
    suspend fun refreshHome(): Map<LoopBoxMediaKind, List<LoopBoxMediaItem>> =
        runCatching { scraper.loadHomePage() }.getOrElse {
            mapOf(
                LoopBoxMediaKind.MUSIC to emptyList(),
                LoopBoxMediaKind.MOVIE to emptyList(),
                LoopBoxMediaKind.SERIES to emptyList()
            )
        }

    suspend fun search(query: String, kind: LoopBoxMediaKind): List<LoopBoxMediaItem> =
        scraper.search(query, kind)
}
