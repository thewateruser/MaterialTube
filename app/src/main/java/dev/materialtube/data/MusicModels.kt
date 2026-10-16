package dev.materialtube.data

enum class MusicType { Song, Video, Album, Single, EP, Playlist, Artist, Unknown;

    val playable get() = this == Song || this == Video
    val isRelease get() = this == Album || this == Single || this == EP
}

data class MusicArtistRef(val name: String, val id: String?)

/** Anything YouTube Music lists: a song/video (id = videoId) or a page (id = browseId). */
data class MusicItem(
    val id: String,
    val type: MusicType,
    val title: String,
    /** e.g. "Song • Artist • 3:21" or "Album • Artist • 2024". */
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val artists: List<MusicArtistRef> = emptyList(),
    /** For albums/playlists: the playlist to play; for tracks: the playlist they came from. */
    val playlistId: String? = null,
    val duration: String? = null,
) {
    /** The video form used by the player. */
    fun toVideo(): VideoItem = VideoItem(
        id = id,
        title = title,
        channel = artists.joinToString(", ") { it.name }.ifEmpty { subtitle },
        channelId = artists.firstOrNull { it.id != null }?.id,
        thumbnail = thumbnail,
        duration = duration,
    )
}

data class MusicShelf(
    val title: String?,
    val items: List<MusicItem>,
    /** "More" page for the shelf, when it has one. */
    val moreBrowseId: String? = null,
    val moreParams: String? = null,
)

/** A YouTube Music page: home/explore/search results, or an album/playlist/artist page. */
data class MusicPage(
    val shelves: List<MusicShelf>,
    val continuation: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    /** Playlist that plays the whole album/playlist. */
    val playlistId: String? = null,
)

enum class MusicSearchFilter(val label: String, val params: String?) {
    All("All", null),
    Songs("Songs", "EgWKAQIIAWoKEAkQBRAKEAMQBA=="),
    Videos("Videos", "EgWKAQIQAWoKEAkQChAFEAMQBA=="),
    Albums("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA=="),
    Artists("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    Playlists("Playlists", "EgeKAQQoAEABagoQCRAKEAUQAxAE"),
}
