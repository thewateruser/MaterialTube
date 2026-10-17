package dev.materialtube.data.innertube

import dev.materialtube.data.MusicItem
import dev.materialtube.data.MusicShelf
import dev.materialtube.data.MusicType
import dev.materialtube.data.MusicPage
import dev.materialtube.data.MusicSearchFilter
import org.json.JSONObject

/**
 * YouTube Music. Its catalogue, search and charts come from YouTube Music's own (signed-out)
 * client; the account's personal music (recommendations, library, radio) comes through the TV
 * client, the only one the account's TV sign-in works with.
 */
class MusicApi(private val api: InnerTube) {

    private val accountClients = listOf(InnerTube.MUSIC, InnerTube.MUSIC_ANDROID, InnerTube.MUSIC_IOS)

    /**
     * The visitor session, client and sign-in state of the last first-page request. YouTube
     * Music only serves continuations to the same visitor, so "more" requests reuse them.
     */
    @Volatile private var visitorData: String? = null
    @Volatile private var lastClient: InnerTube.Client = InnerTube.MUSIC
    @Volatile private var lastSignedIn = false

    /** The visitor session each continuation belongs to (requests run in parallel). */
    private val visitorFor = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun raw(
        endpoint: String,
        body: JSONObject,
        client: InnerTube.Client,
        withAuth: Boolean,
        visitor: String? = visitorData,
    ): JSONObject {
        val r = api.call(endpoint, body, client, withAuth, visitor)
        val v = r.obj("responseContext")?.str("visitorData")
        v?.let { visitorData = it }
        if (v != null) MusicParser.continuation(r)?.let { visitorFor[it] = v }
        return r
    }

    /** YouTube's own flag for whether the request counted as signed in (null when absent). */
    private fun loggedIn(r: JSONObject): Boolean? =
        findFirst(r.opt("responseContext")) { it.str("key") == "logged_in" }?.str("value")?.let { it == "1" }

    private fun rendererCounts(json: Any?, out: MutableMap<String, Int> = sortedMapOf()): Map<String, Int> {
        when (json) {
            is JSONObject -> json.keys().forEach { k ->
                if (k.endsWith("Renderer") || k.endsWith("ViewModel")) out[k] = (out[k] ?: 0) + 1
                rendererCounts(json.opt(k), out)
            }
            is org.json.JSONArray -> for (i in 0 until json.length()) rendererCounts(json.opt(i), out)
        }
        return out
    }

    private fun signedOut(endpoint: String, body: JSONObject): JSONObject {
        lastClient = InnerTube.MUSIC
        lastSignedIn = false
        return raw(endpoint, body, InnerTube.MUSIC, withAuth = false)
    }

    // The account's sign-in is the TV app's, which YouTube Music's own clients refuse (HTTP 400,
    // see diagnostics). So YouTube Music itself is used signed out (catalogue, search, charts),
    // and everything personal comes from the TV client, which does accept the account.

    private fun call(endpoint: String, body: JSONObject): JSONObject = signedOut(endpoint, body)

    private fun browse(browseId: String, params: String? = null): MusicPage {
        val body = JSONObject().put("browseId", browseId).also { b -> params?.let { b.put("params", it) } }
        return MusicParser.page(signedOut("browse", body))
    }

    /** A page through the TV client, signed in. */
    private fun tv(browseId: String): MusicPage =
        Parser.tvMusic(api.call("browse", JSONObject().put("browseId", browseId), InnerTube.TV, withAuth = true))

    private fun continued(endpoint: String, token: String): MusicPage {
        val viaQuery = runCatching {
            MusicParser.page(raw("$endpoint?ctoken=$token&continuation=$token&type=next", JSONObject(), lastClient, lastSignedIn, visitorFor[token] ?: visitorData))
        }.getOrNull()
        if (viaQuery != null && viaQuery.shelves.isNotEmpty()) return viaQuery
        return MusicParser.page(raw(endpoint, JSONObject().put("continuation", token), lastClient, lastSignedIn, visitorFor[token] ?: visitorData))
    }

    /** Next page of any browse result (home, explore, long playlists, TV pages). */
    fun more(token: String): MusicPage {
        if (token.startsWith("tv:")) {
            return Parser.tvMusic(api.call("browse", JSONObject().put("continuation", token.removePrefix("tv:")), InnerTube.TV, withAuth = true))
        }
        return continued("browse", token)
    }

    /**
     * Signed in: the account's own music (the TV app's Music section) first, then YouTube
     * Music's general home. Signed out: just the general home.
     */
    /** YouTube Music's home (general). */
    fun home(): MusicPage = browse("FEmusic_home")

    fun explore(): MusicPage = browse("FEmusic_explore")

    fun newReleases(): MusicPage = browse("FEmusic_new_releases_albums")

    fun charts(): MusicPage = browse("FEmusic_charts")

    fun search(query: String, filter: MusicSearchFilter = MusicSearchFilter.All): MusicPage {
        val body = JSONObject().put("query", query)
        filter.params?.let { body.put("params", it) }
        return MusicParser.page(call("search", body))
    }

    fun searchMore(token: String): MusicPage = continued("search", token)

    /**
     * Album (MPREb_…), playlist (VL…) or artist (UC…) page, or a shelf's "more" page. Private
     * playlists (yours) aren't visible signed out, so those come through the TV client.
     */
    fun page(browseId: String, params: String? = null): MusicPage {
        val public = runCatching { browse(browseId, params) }
        public.getOrNull()?.takeIf { it.shelves.isNotEmpty() }?.let { return it }
        if (api.isSignedIn && params == null) {
            runCatching { tv(browseId) }.getOrNull()?.takeIf { it.shelves.isNotEmpty() }?.let { return it }
        }
        return public.getOrThrow()
    }

    /**
     * The queue that plays after [videoId]: the rest of [playlistId] when given (an album or
     * playlist), otherwise YouTube Music's radio for the song.
     */
    fun queue(videoId: String?, playlistId: String?): Pair<List<MusicItem>, String?> {
        if (api.isSignedIn && videoId != null && playlistId == null) {
            runCatching { tvRadio(videoId) }.getOrNull()?.takeIf { it.first.size >= 3 }?.let { return it }
        }
        return musicQueue(videoId, playlistId)
    }

    /**
     * The account's radio for a song, through the TV client (so it follows your taste). Its
     * "continuation" is the last song, whose own radio continues the queue.
     */
    private fun tvRadio(videoId: String): Pair<List<MusicItem>, String?> {
        val r = api.call("next", JSONObject().put("videoId", videoId).put("playlistId", "RDAMVM$videoId"), InnerTube.TV, withAuth = true)
        val items = Parser.tvMusic(r).shelves.flatMap { it.items }.filter { it.type.playable }
        return items to items.lastOrNull()?.let { "tv:${it.id}" }
    }

    private fun musicQueue(videoId: String?, playlistId: String?): Pair<List<MusicItem>, String?> {
        val body = JSONObject()
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        videoId?.let { body.put("videoId", it) }
        body.put("playlistId", playlistId ?: "RDAMVM$videoId")
        return MusicParser.queue(call("next", body))
    }

    fun queueMore(token: String, playlistId: String): Pair<List<MusicItem>, String?> =
        if (token.startsWith("tv:")) tvRadio(token.removePrefix("tv:")) else musicQueueMore(token, playlistId)

    private fun musicQueueMore(token: String, playlistId: String): Pair<List<MusicItem>, String?> =
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
        val page = tv(section.browseId)
        if (page.shelves.isNotEmpty() || section != LibrarySection.Liked) return page
        // No separate liked-music list: liked songs are also in liked videos.
        return tv("VLLL")
    }

    /** What each Music client returns for this account (for Settings → Copy diagnostics). */
    fun diagnostics(): String = buildString {
        append("== Music sign-in (signed in: ${api.isSignedIn})\n")
        for (client in accountClients) {
            for ((label, id) in listOf("home" to "FEmusic_home", "liked" to "VLLM", "playlists" to "FEmusic_liked_playlists")) {
                val r = runCatching { api.call("browse", JSONObject().put("browseId", id), client, withAuth = api.isSignedIn) }
                append(client.name).append(' ').append(label).append(": ")
                r.onFailure { append("error ").append(it.message) }
                r.onSuccess { json ->
                    val page = MusicParser.page(json)
                    append("logged_in=").append(loggedIn(json)).append(", ")
                        .append(page.shelves.size).append(" shelves, ")
                        .append(page.shelves.sumOf { it.items.size }).append(" items ")
                        .append(page.shelves.take(3).map { it.title })
                    if (page.shelves.isEmpty()) append(" renderers=").append(rendererCounts(json))
                }
                append('\n')
            }
        }
        for ((label, id) in listOf("TV music" to "FEtopics_music", "TV liked music" to "FEmusic_liked_videos", "TV playlists" to "FEplaylist_aggregation", "TV library" to "FEmusic_library_landing")) {
            append(label).append(": ")
            runCatching { tv(id) }
                .onFailure { append("error ").append(it.message) }
                .onSuccess { p -> append(p.shelves.size).append(" shelves, ").append(p.shelves.sumOf { it.items.size }).append(" items ").append(p.shelves.take(4).map { it.title }) }
            append('\n')
        }
    }
    enum class LibrarySection(val label: String, val browseId: String) {
        Liked("Liked music", "FEmusic_liked_videos"),
        Playlists("Playlists", "FEplaylist_aggregation"),
        Library("Library", "FEmusic_library_landing"),
    }
}
