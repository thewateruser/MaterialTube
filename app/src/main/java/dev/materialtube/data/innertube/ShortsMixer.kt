package dev.materialtube.data.innertube

import dev.materialtube.data.FeedPage
import dev.materialtube.data.Shelf
import dev.materialtube.data.VideoItem

/**
 * Mixes Shorts rows into a feed so they show up regularly (about one row per [videosPerRow]
 * videos) instead of a single row lost among hundreds of videos. Shorts come from YouTube's own
 * feed when present, otherwise from a pool filled by (signed-in, so personalised) Shorts searches.
 */
class ShortsMixer(
    private val search: (String) -> FeedPage,
    private val more: (String) -> FeedPage,
    private val videosPerRow: Int = 8,
    private val rowSize: Int = 10,
) {
    private val queries = listOf("shorts", "trending shorts", "funny shorts", "shorts for you")
    private val pool = ArrayDeque<VideoItem>()
    private val used = HashSet<String>()
    private var queryIndex = 0
    private var token: String? = null

    @Synchronized
    fun reset() {
        pool.clear()
        used.clear()
        queryIndex = 0
        token = null
    }

    @Synchronized
    fun mix(page: FeedPage): FeedPage {
        page.shelves.forEach { s -> s.items.filter { it.isShort }.forEach { used += it.id } }
        val out = mutableListOf<Shelf>()
        var videosSinceRow = 0
        var inserted = 0
        for (shelf in page.shelves) {
            out += shelf
            val videos = shelf.items.count { !it.isShort }
            if (videos == 0) {
                videosSinceRow = 0 // the feed already had a Shorts row here
                continue
            }
            videosSinceRow += videos
            if (videosSinceRow >= videosPerRow) {
                val row = take(rowSize)
                if (row.size >= 3) {
                    out += Shelf("Shorts", row)
                    inserted++
                    videosSinceRow = 0
                }
            }
        }
        if (inserted == 0 && page.shelves.isNotEmpty() && page.shelves.none { s -> s.items.any { it.isShort } }) {
            val row = take(rowSize)
            if (row.size >= 3) out.add(minOf(1, out.size), Shelf("Shorts", row))
        }
        return page.copy(shelves = out)
    }

    private fun take(n: Int): List<VideoItem> {
        var attempts = 0
        while (pool.size < n && attempts < 3) {
            attempts++
            if (!refill()) break
        }
        return List(minOf(n, pool.size)) { pool.removeFirst() }
    }

    /** Pulls the next page of Shorts into the pool. */
    private fun refill(): Boolean {
        if (queryIndex >= queries.size) return false
        val page = runCatching {
            val t = token
            if (t != null) more(t) else search(queries[queryIndex])
        }.getOrNull()
        token = page?.continuation
        if (page == null || token == null) {
            queryIndex++
            token = null
        }
        val fresh = page?.shelves.orEmpty().flatMap { it.items }.filter { it.isShort && used.add(it.id) }
        pool.addAll(fresh)
        return fresh.isNotEmpty() || queryIndex < queries.size
    }
}
