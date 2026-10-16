package dev.materialtube.data.innertube

import dev.materialtube.data.MusicArtistRef
import dev.materialtube.data.MusicItem
import dev.materialtube.data.MusicPage
import dev.materialtube.data.MusicShelf
import dev.materialtube.data.MusicType
import org.json.JSONObject

/** Parses YouTube Music (WEB_REMIX) responses. Tolerant: unknown shapes are skipped. */
object MusicParser {

    private val TIME = Regex("""\d{1,2}:\d{2}(:\d{2})?""")

    private val SHELF_KEYS = listOf(
        "musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer",
        "musicImmersiveCarouselShelfRenderer", "gridRenderer", "musicCardShelfRenderer",
        "musicShelfContinuation", "musicPlaylistShelfContinuation", "gridContinuation",
    )

    /** Any music page: shelves of items plus, for albums/playlists/artists, the header. */
    fun page(root: JSONObject): MusicPage {
        val shelves = mutableListOf<MusicShelf>()
        walk(root) { node ->
            val key = SHELF_KEYS.firstOrNull { node.has(it) } ?: return@walk true
            val shelf = node.obj(key)!!
            if (key == "musicCardShelfRenderer") {
                // Search "top result": the card itself plus the items listed under it.
                val top = cardItem(shelf)
                val rest = items(shelf.opt("contents"))
                val all = listOfNotNull(top) + rest.filter { it.id != top?.id }
                if (all.isNotEmpty()) shelves += MusicShelf(text(shelf.opt("header")?.let { findFirst(it) { n -> n.has("title") }?.opt("title") }) ?: "Top result", all)
            } else {
                val list = items(shelf.opt("contents") ?: shelf.opt("items"))
                if (list.isNotEmpty()) {
                    val more = findFirst(shelf.opt("header") ?: shelf.opt("bottomEndpoint")) { it.has("browseEndpoint") }?.obj("browseEndpoint")
                        ?: shelf.obj("bottomEndpoint", "browseEndpoint")
                    shelves += MusicShelf(shelfTitle(shelf), list, more?.str("browseId"), more?.str("params"))
                }
            }
            false
        }
        // Newer search layouts list results as loose items (one per section), not in shelves:
        // group those by kind.
        val inShelves = shelves.flatMap { it.items }.map { it.id }.toHashSet()
        val loose = items(root.opt("contents")).filter { it.id !in inShelves }
        if (loose.isNotEmpty() && shelves.sumOf { it.items.size } < loose.size) {
            loose.groupBy { groupTitle(it.type) }.forEach { (title, list) -> shelves += MusicShelf(title, list) }
        }
        val header = header(root)
        return MusicPage(
            shelves = shelves,
            continuation = continuation(root),
            title = header?.title,
            subtitle = header?.subtitle,
            description = header?.description,
            thumbnail = header?.thumbnail,
            playlistId = header?.playlistId,
        )
    }

    private fun groupTitle(type: MusicType) = when (type) {
        MusicType.Song -> "Songs"
        MusicType.Video -> "Videos"
        MusicType.Album, MusicType.Single, MusicType.EP -> "Albums"
        MusicType.Artist -> "Artists"
        MusicType.Playlist -> "Playlists"
        MusicType.Unknown -> "More"
    }

    private fun shelfTitle(shelf: JSONObject): String? =
        text(shelf.opt("title"))
            ?: shelf.obj("header")?.let { h -> findFirst(h) { it.has("title") }?.let { text(it.opt("title")) } }

    /** The outer list's continuation (home/explore/playlists), not per-shelf ones. */
    fun continuation(root: JSONObject): String? {
        val lists = listOfNotNull(
            findFirst(root.opt("contents")) { it.has("sectionListRenderer") }?.obj("sectionListRenderer"),
            root.obj("continuationContents", "sectionListContinuation"),
            root.obj("continuationContents", "musicPlaylistShelfContinuation"),
            root.obj("continuationContents", "musicShelfContinuation"),
            findFirst(root.opt("contents")) { it.has("musicPlaylistShelfRenderer") }?.obj("musicPlaylistShelfRenderer"),
        )
        for (l in lists) {
            l.arr("continuations")?.objects()?.forEach { c ->
                c.obj("nextContinuationData")?.str("continuation")?.let { return it }
            }
            l.arr("contents")?.objects()?.lastOrNull()?.obj("continuationItemRenderer")?.let { c ->
                findFirst(c) { it.has("continuationCommand") }?.obj("continuationCommand")?.str("token")?.let { return it }
            }
        }
        return null
    }

    /** Items directly in a list (each entry is one item renderer). */
    fun items(list: Any?): List<MusicItem> {
        val out = LinkedHashMap<String, MusicItem>()
        walk(list) { node ->
            val item = item(node)
            if (item != null) {
                if (item.id !in out) out[item.id] = item
                false
            } else {
                true
            }
        }
        return out.values.toList()
    }

    fun item(node: JSONObject): MusicItem? {
        node.obj("musicTwoRowItemRenderer")?.let { return twoRow(it) }
        node.obj("musicResponsiveListItemRenderer")?.let { return listItem(it) }
        node.obj("playlistPanelVideoRenderer")?.let { return panelItem(it) }
        node.obj("musicMultiRowListItemRenderer")?.let { return multiRow(it) }
        return null
    }

    // --- item renderers ---

    private fun twoRow(r: JSONObject): MusicItem? {
        val titleRun = r.obj("title")?.arr("runs")?.objects()?.firstOrNull()
        val title = text(r.opt("title")) ?: return null
        val nav = r.obj("navigationEndpoint") ?: titleRun?.obj("navigationEndpoint")
        val subtitleRuns = r.obj("subtitle")?.arr("runs")?.objects().orEmpty()
        val subtitle = text(r.opt("subtitle"))
        val thumb = thumbnail(r)
        val playlist = playPlaylistId(r)
        nav?.obj("watchEndpoint")?.let { w ->
            val id = w.str("videoId") ?: return null
            return MusicItem(id, videoType(w), title, subtitle, thumb, artists(subtitleRuns), w.str("playlistId"))
        }
        val browse = nav?.obj("browseEndpoint") ?: return null
        val id = browse.str("browseId") ?: return null
        val type = pageType(browse, subtitle)
        return MusicItem(id, type, title, subtitle, thumb, artists(subtitleRuns), playlist ?: playlistFromBrowseId(id))
    }

    private fun listItem(r: JSONObject): MusicItem? {
        val columns = r.arr("flexColumns")?.objects().orEmpty().map { it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") }
        val title = columns.getOrNull(0)?.let { text(it) } ?: return null
        val otherRuns = columns.drop(1).flatMap { it?.arr("runs")?.objects().orEmpty() }
        var parts = columns.drop(1).mapNotNull { c -> c?.let { text(it) } }
            .flatMap { it.split(" • ") }.map { it.trim() }.filter { it.isNotBlank() }
        // Search results put the length in the subtitle ("Song • Artist • Album • 4:08").
        val inlineDuration = parts.lastOrNull { TIME.matches(it) }
        if (inlineDuration != null) parts = parts - inlineDuration
        val subtitle = parts.joinToString(" • ").ifEmpty { null }
        val duration = r.arr("fixedColumns")?.objects()?.firstOrNull()
            ?.obj("musicResponsiveListItemFixedColumnRenderer")?.let { text(it.opt("text")) }
            ?: inlineDuration
        val thumb = thumbnail(r)
        val videoId = r.obj("playlistItemData")?.str("videoId")
            ?: findFirst(r.opt("overlay")) { it.has("watchEndpoint") }?.obj("watchEndpoint")?.str("videoId")
            ?: columns.getOrNull(0)?.let { findFirst(it) { n -> n.has("watchEndpoint") }?.obj("watchEndpoint")?.str("videoId") }
        if (videoId != null) {
            val watch = findFirst(r) { it.has("watchEndpoint") }?.obj("watchEndpoint")
            return MusicItem(
                videoId, watch?.let(::videoType) ?: MusicType.Song, title, subtitle, thumb,
                artists(otherRuns), watch?.str("playlistId"), duration,
            )
        }
        val browse = r.obj("navigationEndpoint", "browseEndpoint") ?: return null
        val id = browse.str("browseId") ?: return null
        return MusicItem(id, pageType(browse, subtitle), title, subtitle, thumb, artists(otherRuns), playPlaylistId(r) ?: playlistFromBrowseId(id), duration)
    }

    private fun panelItem(r: JSONObject): MusicItem? {
        val id = r.str("videoId") ?: return null
        val byline = r.obj("longBylineText") ?: r.obj("shortBylineText")
        return MusicItem(
            id = id,
            type = r.obj("navigationEndpoint", "watchEndpoint")?.let(::videoType) ?: MusicType.Song,
            title = text(r.opt("title")) ?: return null,
            subtitle = text(byline),
            thumbnail = bestThumb(r.obj("thumbnail"))?.let(::large),
            artists = artists(byline?.arr("runs")?.objects().orEmpty()),
            playlistId = r.obj("navigationEndpoint", "watchEndpoint")?.str("playlistId"),
            duration = text(r.opt("lengthText")),
        )
    }

    private fun multiRow(r: JSONObject): MusicItem? {
        val nav = findFirst(r.opt("onTap")) { it.has("watchEndpoint") }?.obj("watchEndpoint") ?: return null
        val id = nav.str("videoId") ?: return null
        return MusicItem(id, MusicType.Song, text(r.opt("title")) ?: return null, text(r.opt("subtitle")), thumbnail(r))
    }

    /** The search "top result" card. */
    private fun cardItem(c: JSONObject): MusicItem? {
        val title = text(c.opt("title")) ?: return null
        val nav = c.obj("title")?.arr("runs")?.objects()?.firstOrNull()?.obj("navigationEndpoint") ?: c.obj("onTap")
        val subtitleRuns = c.obj("subtitle")?.arr("runs")?.objects().orEmpty()
        val thumb = thumbnail(c)
        nav?.obj("watchEndpoint")?.let { w ->
            val id = w.str("videoId") ?: return null
            return MusicItem(id, videoType(w), title, text(c.opt("subtitle")), thumb, artists(subtitleRuns))
        }
        val browse = nav?.obj("browseEndpoint") ?: return null
        val id = browse.str("browseId") ?: return null
        return MusicItem(id, pageType(browse, text(c.opt("subtitle"))), title, text(c.opt("subtitle")), thumb, artists(subtitleRuns), playlistFromBrowseId(id))
    }

    // --- helpers ---

    private fun videoType(w: JSONObject): MusicType {
        val t = findFirst(w) { it.has("musicVideoType") }?.str("musicVideoType").orEmpty()
        return if (t.isEmpty() || t.endsWith("_ATV")) MusicType.Song else MusicType.Video
    }

    private fun pageType(browse: JSONObject, subtitle: String?): MusicType {
        val pageType = findFirst(browse) { it.has("pageType") }?.str("pageType").orEmpty()
        val id = browse.str("browseId").orEmpty()
        val sub = subtitle.orEmpty()
        return when {
            pageType.endsWith("ARTIST") || pageType.endsWith("USER_CHANNEL") || id.startsWith("UC") -> MusicType.Artist
            pageType.endsWith("ALBUM") || id.startsWith("MPRE") -> when {
                sub.startsWith("Single") -> MusicType.Single
                sub.startsWith("EP") -> MusicType.EP
                else -> MusicType.Album
            }
            pageType.endsWith("PLAYLIST") || id.startsWith("VL") -> MusicType.Playlist
            else -> MusicType.Unknown
        }
    }

    private fun playlistFromBrowseId(id: String): String? = if (id.startsWith("VL")) id.removePrefix("VL") else null

    private fun playPlaylistId(r: JSONObject): String? =
        findFirst(r.opt("thumbnailOverlay") ?: r.opt("overlay")) { it.has("watchPlaylistEndpoint") || it.has("watchEndpoint") }
            ?.let { it.obj("watchPlaylistEndpoint") ?: it.obj("watchEndpoint") }?.str("playlistId")

    private fun artists(runs: List<JSONObject>): List<MusicArtistRef> = runs.mapNotNull { run ->
        val browse = run.obj("navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
        val id = browse.str("browseId") ?: return@mapNotNull null
        val type = findFirst(browse) { it.has("pageType") }?.str("pageType").orEmpty()
        if (type.endsWith("ARTIST") || type.endsWith("USER_CHANNEL") || (type.isEmpty() && id.startsWith("UC"))) {
            MusicArtistRef(run.optString("text"), id)
        } else {
            null
        }
    }.distinctBy { it.id }

    private fun thumbnail(r: JSONObject): String? {
        val node = r.obj("thumbnailRenderer") ?: r.obj("thumbnail") ?: r
        val t = findFirst(node) { it.has("thumbnails") }
        return bestThumb(t)?.let(::large)
    }

    /** Music thumbnails come small ("=w60-h60"); ask for a sharp square. */
    fun large(url: String): String =
        url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544").replace(Regex("=s\\d+"), "=s544")

    private class Header(
        val title: String?, val subtitle: String?, val description: String?, val thumbnail: String?, val playlistId: String?,
    )

    private val HEADER_KEYS = listOf(
        "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer",
        "musicVisualHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer", "musicHeaderRenderer",
    )

    private fun header(root: JSONObject): Header? {
        val holder = findFirst(root) { n -> HEADER_KEYS.any { n.has(it) } } ?: return null
        var h = HEADER_KEYS.firstNotNullOf { holder.obj(it) }
        // Editable (own) playlists wrap the real header.
        h.obj("header", "musicResponsiveHeaderRenderer")?.let { h = it }
        val strap = text(h.opt("straplineTextOne"))
        val subtitle = listOfNotNull(strap, text(h.opt("subtitle")), text(h.opt("secondSubtitle")))
            .filter { it.isNotBlank() }.joinToString(" • ").ifEmpty { null }
        val description = text(h.opt("description"))
            ?: findFirst(h.opt("description")) { it.has("description") }?.let { text(it.opt("description")) }
        val playlist = findFirst(h) { it.has("watchPlaylistEndpoint") }?.obj("watchPlaylistEndpoint")?.str("playlistId")
            ?: findFirst(h) { it.has("watchEndpoint") }?.obj("watchEndpoint")?.str("playlistId")
            ?: findFirst(root.opt("microformat")) { it.has("urlCanonical") }?.str("urlCanonical")
                ?.substringAfter("list=", "")?.takeIf { it.isNotEmpty() }
        return Header(text(h.opt("title")), subtitle, description, thumbnail(h), playlist)
    }

    /** Up-next / radio queue from a WEB_REMIX `next` response, plus its continuation. */
    fun queue(root: JSONObject): Pair<List<MusicItem>, String?> {
        val panel = findFirst(root) { it.has("playlistPanelRenderer") }?.obj("playlistPanelRenderer")
            ?: root.obj("continuationContents", "playlistPanelContinuation")
            ?: return emptyList<MusicItem>() to null
        val items = items(panel.opt("contents"))
        val next = panel.arr("continuations")?.objects()?.firstNotNullOfOrNull { c ->
            (c.obj("nextRadioContinuationData") ?: c.obj("nextContinuationData"))?.str("continuation")
        }
        return items to next
    }
}
