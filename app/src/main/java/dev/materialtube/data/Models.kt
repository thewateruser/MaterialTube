package dev.materialtube.data

data class VideoItem(
    val id: String,
    val title: String,
    val channel: String? = null,
    val channelId: String? = null,
    /** Secondary line, e.g. "1.2M views • 3 days ago". */
    val meta: String? = null,
    val duration: String? = null,
    val thumbnail: String? = null,
    val isLive: Boolean = false,
    /** 0-100 when YouTube reports a resume position for this account. */
    val watchedPercent: Int? = null,
    /** A YouTube Short (vertical, shown in the Shorts row / player). */
    val isShort: Boolean = false,
)

data class ChannelItem(
    val id: String,
    val title: String,
    val avatar: String? = null,
    /** e.g. "1.2M subscribers" */
    val subtitle: String? = null,
)

data class Shelf(val title: String?, val items: List<VideoItem>)

data class FeedPage(
    val shelves: List<Shelf>,
    val continuation: String?,
    /** Channels found in the response (search results, subscription list). */
    val channels: List<ChannelItem> = emptyList(),
)

data class PlaybackTracking(
    val playbackUrl: String?,
    val watchtimeUrl: String?,
    /** The InnerTube client that issued these URLs; pings must identify as the same client. */
    val clientName: String = "TVHTML5",
    val clientVersion: String = "",
)

data class VideoDetails(
    val id: String,
    val title: String,
    val author: String?,
    val channelId: String?,
    val description: String?,
    val viewCount: Long?,
    val lengthSeconds: Long?,
    val isLive: Boolean,
    val thumbnail: String?,
)

data class PlayerInfo(
    val details: VideoDetails?,
    val tracking: PlaybackTracking?,
    val playabilityStatus: String?,
)

enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

data class CommentSort(val label: String, val token: String)

data class NextInfo(
    val related: List<VideoItem>,
    val likeStatus: LikeStatus?,
    val subscribed: Boolean?,
    val channelId: String?,
    val channelName: String?,
    val channelAvatar: String?,
    val likeCountText: String?,
)

data class ChannelPage(
    val title: String?,
    val avatar: String?,
    val subscribed: Boolean?,
    val feed: FeedPage,
    val banner: String? = null,
    /** e.g. "@MrBeast • 400M subscribers • 900 videos" */
    val subtitle: String? = null,
)

enum class ChannelTab(val label: String, val params: String?) {
    Home("Home", null),
    Videos("Videos", "EgZ2aWRlb3PyBgQKAjoA"),
    Shorts("Shorts", "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"),
    Live("Live", "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"),

    /** Posts and About come from their own requests (see InnerTube.channelPosts / channelAbout). */
    Posts("Posts", null),
    About("About", null),
}

/** A channel community post. */
data class Post(
    val id: String,
    val author: String?,
    val authorAvatar: String?,
    val text: String,
    val published: String?,
    val likes: String?,
    val comments: String?,
    val images: List<String> = emptyList(),
    val video: VideoItem? = null,
    val poll: List<String> = emptyList(),
)

data class PostsPage(val posts: List<Post>, val continuation: String?)

data class ChannelLink(val title: String, val url: String)

/** The channel's About panel: description, stats ("1M subscribers", "Joined ..."), links. */
data class ChannelAbout(
    val description: String?,
    val stats: List<String>,
    val links: List<ChannelLink>,
)
