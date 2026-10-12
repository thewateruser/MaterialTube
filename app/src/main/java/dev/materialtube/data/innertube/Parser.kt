package dev.materialtube.data.innertube

import dev.materialtube.data.ChannelItem
import dev.materialtube.data.ChannelPage
import dev.materialtube.data.CommentSort
import dev.materialtube.data.FeedPage
import dev.materialtube.data.LikeStatus
import dev.materialtube.data.NextInfo
import dev.materialtube.data.PlaybackTracking
import dev.materialtube.data.PlayerInfo
import dev.materialtube.data.Shelf
import dev.materialtube.data.VideoDetails
import dev.materialtube.data.VideoItem
import org.json.JSONObject

/**
 * Turns InnerTube responses into app models.
 *
 * YouTube reshuffles its renderer tree all the time, so instead of hard-coding full paths the
 * parser walks the whole response and recognises the leaf renderers it knows (TV tiles, web
 * video renderers and the newer lockup view-models). That keeps it working across most layout
 * changes as long as the leaf shapes survive.
 */
object Parser {

    private val CONTAINER_SKIP = setOf("horizontalListRenderer", "horizontalListContinuation")
    private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

    fun feed(root: JSONObject): FeedPage {
        val shelves = mutableListOf<Shelf>()
        walk(root) { node ->
            val shelf = node.optJSONObject("shelfRenderer")
                ?: node.optJSONObject("richShelfRenderer")
                ?: node.optJSONObject("gridRenderer")
                ?: node.optJSONObject("gridContinuation")
            if (shelf != null) {
                val items = videos(shelf)
                if (items.isNotEmpty()) shelves += Shelf(shelfTitle(shelf), items)
                false
            } else {
                true
            }
        }
        val seen = shelves.flatMap { s -> s.items.map { it.id } }.toSet()
        // Anything outside a shelf (flat lists, rich grids) becomes one untitled shelf.
        val loose = videos(root).filter { it.id !in seen }
        if (loose.isNotEmpty()) shelves += Shelf(null, loose)
        return FeedPage(shelves, continuation(root), channels(root))
    }

    /** Channel entries anywhere in [root]: search results, the subscriptions sidebar, channel grids. */
    fun channels(root: Any?): List<ChannelItem> {
        val out = LinkedHashMap<String, ChannelItem>()
        walk(root) { node ->
            val c = channelItem(node)
            if (c != null) {
                if (c.id !in out) out[c.id] = c
                false
            } else {
                true
            }
        }
        return out.values.toList()
    }

    private fun channelItem(node: JSONObject): ChannelItem? {
        node.obj("tileRenderer")?.let { t ->
            if ("CHANNEL" !in t.str("contentType").orEmpty()) return null
            val id = t.obj("onSelectCommand", "browseEndpoint")?.str("browseId") ?: t.str("contentId") ?: return null
            if (!id.startsWith("UC")) return null
            val meta = t.obj("metadata", "tileMetadataRenderer")
            val lines = meta?.arr("lines")?.objects()?.mapNotNull { line ->
                line.obj("lineRenderer")?.arr("items")?.objects()
                    ?.mapNotNull { text(it.obj("lineItemRenderer")?.opt("text")) }?.joinToString(" ")?.trim()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            return ChannelItem(
                id, text(meta?.opt("title")) ?: return null,
                bestThumb(t.obj("header", "tileHeaderRenderer", "thumbnail")),
                lines.firstOrNull(),
            )
        }
        node.obj("lockupViewModel")?.let { l ->
            if ("CHANNEL" !in l.str("contentType").orEmpty()) return null
            val id = l.str("contentId")?.takeIf { it.startsWith("UC") } ?: return null
            val meta = l.obj("metadata", "lockupMetadataViewModel")
            var avatar: String? = null
            walk(l.opt("contentImage")) { n -> if (avatar == null) avatar = bestThumb(n.obj("image")); avatar == null }
            return ChannelItem(id, text(meta?.opt("title")) ?: return null, avatar)
        }
        for (key in listOf("channelRenderer", "gridChannelRenderer", "compactChannelRenderer")) {
            node.obj(key)?.let { c ->
                val id = c.str("channelId") ?: return null
                return ChannelItem(
                    id, text(c.opt("title")) ?: text(c.opt("displayName")) ?: return null,
                    bestThumb(c.obj("thumbnail")),
                    text(c.opt("subscriberCountText")) ?: text(c.opt("videoCountText")),
                )
            }
        }
        // TV subscriptions sidebar: one tab per channel, and TV search channel lockups.
        for (key in listOf("tabRenderer", "avatarLockupRenderer")) {
            node.obj(key)?.let { c ->
                val id = findFirst(c) { it.obj("browseEndpoint")?.str("browseId")?.startsWith("UC") == true }
                    ?.obj("browseEndpoint")?.str("browseId") ?: return null
                val title = text(c.opt("title")) ?: findFirst(c) { it.has("title") }?.let { text(it.opt("title")) } ?: return null
                var avatar: String? = null
                walk(c) { n ->
                    if (avatar == null) avatar = bestThumb(n.obj("thumbnail")) ?: bestThumb(n.obj("avatar"))
                    avatar == null
                }
                return ChannelItem(id, title, avatar, text(c.opt("subtitle")))
            }
        }
        return null
    }

    /** Comment sort options ("Top comments", "Newest first") from a web comments response. */
    fun commentSorts(root: JSONObject): List<CommentSort> {
        val menu = findFirst(root) { it.has("sortFilterSubMenuRenderer") }?.obj("sortFilterSubMenuRenderer") ?: return emptyList()
        return menu.arr("subMenuItems")?.objects()?.mapNotNull { item ->
            val label = item.str("title") ?: text(item.opt("title")) ?: return@mapNotNull null
            val token = findFirst(item) { it.has("continuationCommand") }?.obj("continuationCommand")?.str("token")
                ?: return@mapNotNull null
            CommentSort(label, token)
        }.orEmpty()
    }

    /** Continuation token of the comments section in a web /next response. */
    fun commentsToken(root: JSONObject): String? {
        val section = findFirst(root) {
            it.obj("itemSectionRenderer")?.str("targetId") == "comments-section" ||
                it.obj("itemSectionRenderer")?.str("sectionIdentifier") == "comment-item-section"
        }?.obj("itemSectionRenderer") ?: return null
        return findFirst(section) { it.has("continuationCommand") }?.obj("continuationCommand")?.str("token")
    }

    private fun shelfTitle(shelf: JSONObject): String? =
        text(shelf.opt("title"))
            ?: text(shelf.obj("headerRenderer", "shelfHeaderRenderer")?.opt("title"))
            ?: shelf.obj("header", "gridHeaderRenderer")?.let { text(it.opt("title")) }
            ?: findFirst(shelf.opt("headerRenderer") ?: shelf.opt("header")) { it.has("title") }?.let { text(it.opt("title")) }

    /** Continuation token for the outer (vertical) list, ignoring per-row continuations. */
    fun continuation(root: JSONObject): String? {
        var token: String? = null
        walk(root) { node ->
            if (token != null) return@walk false
            node.arr("continuations")?.objects()?.firstOrNull()?.let { c ->
                token = c.obj("nextContinuationData")?.str("continuation")
                    ?: c.obj("reloadContinuationData")?.str("continuation")
            }
            node.obj("continuationItemRenderer", "continuationEndpoint", "continuationCommand")
                ?.str("token")?.let { if (token == null) token = it }
            token == null && node.keys().asSequence().none { it in CONTAINER_SKIP }
        }
        return token
    }

    fun videos(root: Any?, excludeId: String? = null): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        walk(root) { node ->
            val item = video(node)
            if (item != null) {
                if (item.id != excludeId && item.id !in out) out[item.id] = item
                false
            } else {
                true
            }
        }
        return out.values.toList()
    }

    /** Parses [node] if it is (or directly wraps) a known video renderer. */
    fun video(node: JSONObject): VideoItem? {
        node.optJSONObject("tileRenderer")?.let { return tile(it) }
        node.optJSONObject("lockupViewModel")?.let { return lockup(it) }
        node.optJSONObject("shortsLockupViewModel")?.let { return shortsLockup(it) }
        for (key in VIDEO_RENDERERS) node.optJSONObject(key)?.let { return classic(it) }
        return null
    }

    private val VIDEO_RENDERERS = listOf(
        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer",
        "videoWithContextRenderer", "playlistVideoRenderer", "playlistPanelVideoRenderer",
        "reelItemRenderer",
    )

    private fun tile(t: JSONObject): VideoItem? {
        val type = t.str("contentType").orEmpty()
        if ("CHANNEL" in type || "PLAYLIST" in type) return null
        val watch = t.obj("onSelectCommand", "watchEndpoint")
        val reel = t.obj("onSelectCommand", "reelWatchEndpoint")
        val id = watch?.str("videoId") ?: reel?.str("videoId")
            ?: t.str("contentId")?.takeIf { "VIDEO" in type || "SHORT" in type } ?: return null
        val short = reel != null || "SHORT" in type
        val header = t.obj("header", "tileHeaderRenderer")
        val meta = t.obj("metadata", "tileMetadataRenderer")
        val lines = meta?.arr("lines")?.objects()?.mapNotNull { line ->
            line.obj("lineRenderer")?.arr("items")?.objects()
                ?.mapNotNull { text(it.obj("lineItemRenderer")?.opt("text")) }
                ?.joinToString(" ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        var duration: String? = null
        var live = false
        var watched: Int? = null
        header?.arr("thumbnailOverlays")?.objects()?.forEach { o ->
            o.obj("thumbnailOverlayTimeStatusRenderer")?.let {
                duration = text(it.opt("text"))
                if (it.str("style") == "LIVE") live = true
            }
            o.obj("thumbnailOverlayResumePlaybackRenderer")?.let { watched = it.optInt("percentDurationWatched") }
        }
        return VideoItem(
            id = id,
            title = text(meta?.opt("title")) ?: return null,
            channel = lines.getOrNull(0),
            channelId = channelIdIn(t),
            meta = lines.drop(1).joinToString(" • ").takeIf { it.isNotEmpty() },
            duration = duration?.takeIf { DURATION.matches(it) },
            thumbnail = bestThumb(header?.obj("thumbnail")) ?: thumbFor(id),
            isLive = live || duration.equals("LIVE", ignoreCase = true),
            watchedPercent = watched,
            isShort = short,
        )
    }

    private fun lockup(l: JSONObject): VideoItem? {
        val type = l.str("contentType").orEmpty()
        // VIDEO, MUSIC (music videos) and SHORT are all playable; skip channels/playlists/etc.
        if (type.isNotEmpty() && listOf("VIDEO", "MUSIC", "SHORT").none { it in type }) return null
        val id = l.str("contentId") ?: return null
        val meta = l.obj("metadata", "lockupMetadataViewModel")
        val rows = meta?.obj("metadata", "contentMetadataViewModel")?.arr("metadataRows")?.objects()
            ?.map { row ->
                row.arr("metadataParts")?.objects()?.mapNotNull { text(it.opt("text")) }.orEmpty()
            }?.filter { it.isNotEmpty() }.orEmpty()
        val image = l.obj("contentImage", "thumbnailViewModel")
        var duration: String? = null
        walk(image) { n ->
            n.obj("thumbnailBadgeViewModel")?.let { duration = duration ?: text(it.opt("text")) }
            duration == null
        }
        return VideoItem(
            id = id,
            title = text(meta?.opt("title")) ?: return null,
            channel = rows.getOrNull(0)?.firstOrNull(),
            channelId = channelIdIn(l),
            meta = (rows.getOrNull(0)?.drop(1).orEmpty() + rows.drop(1).flatten())
                .joinToString(" • ").takeIf { it.isNotEmpty() },
            duration = duration?.takeIf { DURATION.matches(it) },
            thumbnail = bestThumb(image?.obj("image")) ?: thumbFor(id),
            isLive = duration.equals("LIVE", ignoreCase = true),
            isShort = "SHORT" in type,
        )
    }

    /** Web/mobile Shorts shelf item. */
    private fun shortsLockup(l: JSONObject): VideoItem? {
        val id = findFirst(l) { it.has("reelWatchEndpoint") }?.obj("reelWatchEndpoint")?.str("videoId")
            ?: l.str("entityId")?.substringAfterLast("-")?.takeIf { it.length == 11 }
            ?: return null
        val overlay = l.obj("overlayMetadata")
        return VideoItem(
            id = id,
            title = text(overlay?.opt("primaryText")) ?: text(l.obj("accessibilityText")) ?: return null,
            meta = text(overlay?.opt("secondaryText")),
            thumbnail = bestThumb(l.obj("thumbnail")) ?: "https://i.ytimg.com/vi/$id/oardefault.jpg",
            isShort = true,
        )
    }

    private fun classic(v: JSONObject): VideoItem? {
        val id = v.str("videoId") ?: return null
        val byline = v.opt("longBylineText") ?: v.opt("shortBylineText") ?: v.opt("ownerText")
        var duration = text(v.opt("lengthText"))
        var live = false
        v.arr("thumbnailOverlays")?.objects()?.forEach { o ->
            o.obj("thumbnailOverlayTimeStatusRenderer")?.let {
                if (duration == null) duration = text(it.opt("text"))
                if (it.str("style") == "LIVE") live = true
            }
        }
        val meta = listOfNotNull(
            text(v.opt("shortViewCountText")) ?: text(v.opt("viewCountText")),
            text(v.opt("publishedTimeText")),
        ).joinToString(" • ").takeIf { it.isNotEmpty() }
        return VideoItem(
            id = id,
            title = text(v.opt("title")) ?: text(v.opt("headline")) ?: return null,
            channel = text(byline),
            channelId = (byline as? JSONObject)?.arr("runs")?.objects()?.firstOrNull()
                ?.obj("navigationEndpoint", "browseEndpoint")?.str("browseId"),
            meta = meta,
            duration = duration?.takeIf { DURATION.matches(it) },
            thumbnail = bestThumb(v.obj("thumbnail")) ?: thumbFor(id),
            isLive = live,
            isShort = v.has("headline") || findFirst(v.opt("navigationEndpoint")) { it.has("reelWatchEndpoint") } != null,
        )
    }

    private fun channelIdIn(node: JSONObject): String? =
        findFirst(node) { it.str("browseId")?.startsWith("UC") == true }?.str("browseId")

    /** yt3 avatar URLs carry their size ("=s72-..."); ask for a sharper one. */
    fun largeAvatar(url: String) = url.replace(Regex("=s\\d+-"), "=s256-")

    fun thumbFor(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    fun player(root: JSONObject): PlayerInfo {
        val d = root.obj("videoDetails")
        val details = d?.str("videoId")?.let { id ->
            VideoDetails(
                id = id,
                title = d.str("title").orEmpty(),
                author = d.str("author"),
                channelId = d.str("channelId"),
                description = d.str("shortDescription"),
                viewCount = d.str("viewCount")?.toLongOrNull(),
                lengthSeconds = d.str("lengthSeconds")?.toLongOrNull(),
                isLive = d.optBoolean("isLive") || d.optBoolean("isLiveContent") && d.str("lengthSeconds") == "0",
                thumbnail = bestThumb(d.obj("thumbnail")),
            )
        }
        val tracking = root.obj("playbackTracking")?.let {
            PlaybackTracking(
                playbackUrl = it.obj("videostatsPlaybackUrl")?.str("baseUrl"),
                watchtimeUrl = it.obj("videostatsWatchtimeUrl")?.str("baseUrl"),
            )
        }
        return PlayerInfo(details, tracking, root.obj("playabilityStatus")?.str("status"))
    }

    fun next(root: JSONObject, videoId: String): NextInfo {
        val likeStatus = findFirst(root) { it.has("likeButtonRenderer") }
            ?.obj("likeButtonRenderer")?.str("likeStatus")
            ?.let { runCatching { LikeStatus.valueOf(it) }.getOrNull() }
            ?: findFirst(root) {
                it.obj("toggleButtonRenderer", "defaultIcon")?.str("iconType") == "LIKE"
            }?.obj("toggleButtonRenderer")?.let {
                if (it.optBoolean("isToggled")) LikeStatus.LIKE else LikeStatus.INDIFFERENT
            }
        val likeCount = findFirst(root) { it.has("likeButtonRenderer") }
            ?.obj("likeButtonRenderer")?.let { text(it.opt("likeCountText")) ?: text(it.opt("likeCountWithLikeText")) }
        val sub = findFirst(root) { it.has("subscribeButtonRenderer") }?.obj("subscribeButtonRenderer")
        val owner = findFirst(root) { it.has("videoOwnerRenderer") }?.obj("videoOwnerRenderer")
        return NextInfo(
            related = videos(root, excludeId = videoId),
            likeStatus = likeStatus,
            subscribed = sub?.takeIf { it.has("subscribed") }?.optBoolean("subscribed"),
            channelId = sub?.str("channelId")
                ?: owner?.obj("navigationEndpoint", "browseEndpoint")?.str("browseId"),
            channelName = text(owner?.opt("title")),
            channelAvatar = bestThumb(owner?.obj("thumbnail")),
            likeCountText = likeCount,
        )
    }

    fun channel(root: JSONObject): ChannelPage {
        val meta = root.obj("metadata", "channelMetadataRenderer")
        val header = root.obj("header")
        val sub = findFirst(root) { it.has("subscribeButtonRenderer") }?.obj("subscribeButtonRenderer")
        val tvHeader = findFirst(root) { it.has("channelHeaderRenderer") }?.obj("channelHeaderRenderer")
        val subtitle = tvHeader?.opt("subtitle")?.let { sub ->
            (sub as? JSONObject)?.obj("lineRenderer")?.arr("items")?.objects()
                ?.mapNotNull { text(it.obj("lineItemRenderer")?.opt("text")) }
                ?.filter { it != "•" }?.joinToString(" • ")
                ?: text(sub)
        }
        return ChannelPage(
            banner = bestThumb(tvHeader?.obj("backgroundImage")) ?: bestThumb(meta?.obj("banner")),
            subtitle = subtitle?.takeIf { it.isNotBlank() },
            title = meta?.str("title")
                ?: text(tvHeader?.opt("title"))
                ?: findFirst(tvHeader) { it.has("title") }?.let { text(it.opt("title")) }
                ?: findFirst(header) { it.has("title") }?.let { text(it.opt("title")) },
            avatar = (bestThumb(meta?.obj("avatar"))
                ?: bestThumb(tvHeader?.obj("avatar"))
                ?: bestThumb(tvHeader?.obj("thumbnail"))
                ?: findFirst(header) { it.has("avatar") }?.let { bestThumb(it.obj("avatar")) })?.let(::largeAvatar),
            subscribed = sub?.takeIf { it.has("subscribed") }?.optBoolean("subscribed"),
            feed = feed(root),
        )
    }
}
