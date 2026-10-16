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

    /**
     * The visitor session and sign-in state of the last first-page request. YouTube Music only
     * serves continuations to the same visitor, so "more" requests reuse them.
     */
    @Volatile private var visitorData: String? = null
    @Volatile private var lastSignedIn = false

    private fun raw(endpoint: String, body: JSONObject, withAuth: Boolean): JSONObject {
        val r = api.call(endpoint, body, InnerTube.MUSIC, withAuth, visitorData)
        r.obj("responseContext")?.str("visitorData")?.let { visitorData = it }
        return r
    }

    private fun call(endpoint: String, body: JSONObject): JSONObject {
        if (api.isSignedIn) {
            runCatching { return raw(endpoint, body, withAuth = true).also { lastSignedIn = true } }
                .onFailure { if (it !is InnerTubeException) throw it }
        }
        return raw(endpoint, JSONObject(body.toString()), withAuth = false).also { lastSignedIn = false }
    }

    private fun browse(browseId: String, params: String? = null): MusicPage {
        fun body() = JSONObject().put("browseId", browseId).also { b -> params?.let { b.put("params", it) } }
        if (api.isSignedIn) {
            // An accepted-but-empty signed-in answer also falls back to the signed-out page.
            runCatching { MusicParser.page(raw("browse", body(), withAuth = true)) }
                .getOrNull()?.takeIf { it.shelves.isNotEmpty() }?.let { lastSignedIn = true; return it }
        }
        lastSignedIn = false
        return MusicParser.page(raw("browse", body(), withAuth = false))
    }

    /** Next page of any browse result (home, explore, long playlists). */
    fun more(token: String): MusicPage {
        val viaQuery = runCatching { MusicParser.page(raw("browse?ctoken=$token&continuation=$token&type=next", JSONObject(), lastSignedIn)) }
            .getOrNull()
        if (viaQuery != null && viaQuery.shelves.isNotEmpty()) return viaQuery
        return MusicParser.page(raw("browse", JSONObject().put("continuation", token), lastSignedIn))
    }

    fun home(): MusicPage = browse("FEmusic_home")

    fun explore(): MusicPage = browse("FEmusic_explore")

    fun newReleases(): MusicPage = browse("FEmusic_new_releases_albums")

    fun charts(): MusicPage = browse("FEmusic_charts")

    fun search(query: String, filter: MusicSearchFilter = MusicSearchFilter.All): MusicPage {
        val body = JSONObject().put("query", query)
        filter.params?.let { body.put("params", it) }
        return MusicParser.page(call("search", body))
    }

    fun searchMore(token: String): MusicPage {
        val viaQuery = runCatching { MusicParser.page(raw("search?ctoken=$token&continuation=$token&type=next", JSONObject(), lastSignedIn)) }
            .getOrNull()
        if (viaQuery != null && viaQuery.shelves.isNotEmpty()) return viaQuery
        return MusicParser.page(raw("search", JSONObject().put("continuation", token), lastSignedIn))
    }

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
