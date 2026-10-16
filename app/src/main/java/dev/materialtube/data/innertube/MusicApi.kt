package dev.materialtube.data.innertube

import dev.materialtube.data.MusicItem
import dev.materialtube.data.MusicPage
import dev.materialtube.data.MusicSearchFilter
import org.json.JSONObject

/**
 * YouTube Music, through its own web client. Signed in, requests carry the account (so Home is
 * your music recommendations); if YouTube Music refuses the account's token, they fall back to
 * signed out so the tab still works.
 */
class MusicApi(private val api: InnerTube) {

    private fun call(endpoint: String, body: JSONObject): JSONObject {
        if (api.isSignedIn) {
            runCatching { return api.call(endpoint, body, InnerTube.MUSIC, withAuth = true) }
                .onFailure { if (it !is InnerTubeException) throw it }
        }
        return api.call(endpoint, JSONObject(body.toString()), InnerTube.MUSIC, withAuth = false)
    }

    private fun browse(browseId: String, params: String? = null): MusicPage {
        fun body() = JSONObject().put("browseId", browseId).also { b -> params?.let { b.put("params", it) } }
        if (api.isSignedIn) {
            // An accepted-but-empty signed-in answer also falls back to the signed-out page.
            runCatching { MusicParser.page(api.call("browse", body(), InnerTube.MUSIC, withAuth = true)) }
                .getOrNull()?.takeIf { it.shelves.isNotEmpty() }?.let { return it }
        }
        return MusicParser.page(api.call("browse", body(), InnerTube.MUSIC, withAuth = false))
    }

    /** Next page of any browse result (home, explore, long playlists). */
    fun more(token: String): MusicPage =
        MusicParser.page(call("browse?ctoken=$token&continuation=$token&type=next", JSONObject()))

    fun home(): MusicPage = browse("FEmusic_home")

    fun explore(): MusicPage = browse("FEmusic_explore")

    fun newReleases(): MusicPage = browse("FEmusic_new_releases_albums")

    fun charts(): MusicPage = browse("FEmusic_charts")

    fun search(query: String, filter: MusicSearchFilter = MusicSearchFilter.All): MusicPage {
        val body = JSONObject().put("query", query)
        filter.params?.let { body.put("params", it) }
        return MusicParser.page(call("search", body))
    }

    fun searchMore(token: String): MusicPage =
        MusicParser.page(call("search?ctoken=$token&continuation=$token&type=next", JSONObject()))

    /** Album (MPREb_…), playlist (VL…) or artist (UC…) page, or a shelf's "more" page. */
    fun page(browseId: String, params: String? = null): MusicPage = browse(browseId, params)

    /**
     * The queue that plays after [videoId]: the rest of [playlistId] when given (an album or
     * playlist), otherwise YouTube Music's radio for the song.
     */
    fun queue(videoId: String?, playlistId: String?): Pair<List<MusicItem>, String?> {
        val body = JSONObject()
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        videoId?.let { body.put("videoId", it) }
        body.put("playlistId", playlistId ?: "RDAMVM$videoId")
        return MusicParser.queue(call("next", body))
    }

    fun queueMore(token: String, playlistId: String): Pair<List<MusicItem>, String?> =
        MusicParser.queue(
            call(
                "next",
                JSONObject().put("continuation", token).put("playlistId", playlistId)
                    .put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true),
            ),
        )

    /** Library sections (need sign-in): liked songs, playlists, albums, artists. */
    fun library(section: LibrarySection): MusicPage {
        if (!api.isSignedIn) throw InnerTubeException("Sign in to see your music library")
        return MusicParser.page(api.call("browse", JSONObject().put("browseId", section.browseId), InnerTube.MUSIC, withAuth = true))
    }

    enum class LibrarySection(val label: String, val browseId: String) {
        Liked("Liked music", "VLLM"),
        Playlists("Playlists", "FEmusic_liked_playlists"),
        Albums("Albums", "FEmusic_liked_albums"),
        Artists("Artists", "FEmusic_library_corpus_track_artists"),
    }
}
