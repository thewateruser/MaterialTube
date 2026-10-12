package dev.materialtube.data.innertube

import dev.materialtube.data.ChannelPage
import dev.materialtube.data.FeedPage
import dev.materialtube.data.NextInfo
import dev.materialtube.data.PlayerInfo
import dev.materialtube.data.auth.TvOAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import dev.materialtube.data.net.isUnreachable
import java.io.IOException
import java.util.Locale

class InnerTubeException(message: String, val code: Int = 0) : IOException(message)

/**
 * Client for YouTube's private "InnerTube" API, speaking as the TV app (TVHTML5) so that the
 * OAuth tokens from [TvOAuth] are accepted. Everything that feeds the recommendation algorithm
 * (home, up next, subscriptions, history, likes, subscriptions) goes through here, signed in as
 * the user.
 */
class InnerTube(
    private val http: OkHttpClient,
    private val auth: TvOAuth?,
    private val locale: () -> Locale = { Locale.getDefault() },
    /** InnerTube hosts, tried in order when one is unreachable. */
    private val hosts: List<String> = HOSTS,
) {
    @Volatile private var workingHost = 0

    fun home(): FeedPage {
        // The TV app calls its home "default"; fall back to the web id if that ever goes away.
        val page = Parser.feed(call("browse", JSONObject().put("browseId", "default")))
        if (page.shelves.isNotEmpty()) return page
        return Parser.feed(call("browse", JSONObject().put("browseId", "FEwhat_to_watch")))
    }

    /** Subscription uploads plus the list of subscribed channels (for the channel row). */
    /** Home feed with a Shorts row: YouTube's own if the feed has one, otherwise from the Shorts feed. */
    fun homeWithShorts(): FeedPage {
        val page = home()
        if (page.shelves.any { s -> s.items.any { it.isShort } }) return page
        val shorts = runCatching { shortsShelf() }.getOrNull() ?: return page
        val shelves = page.shelves.toMutableList()
        shelves.add(minOf(1, shelves.size), shorts)
        return page.copy(shelves = shelves)
    }

    /**
     * A row of Shorts for when the home feed has none. YouTube's Shorts tab isn't available to the
     * TV client, but its (signed-in, so personalised) search returns Shorts lockups.
     */
    fun shortsShelf(): dev.materialtube.data.Shelf? {
        val items = search("shorts").shelves.flatMap { it.items }.filter { it.isShort }.distinctBy { it.id }
        return if (items.size >= 3) dev.materialtube.data.Shelf("Shorts", items.take(30)) else null
    }

    fun subscriptions(): FeedPage {
        val page = browse("FEsubscriptions")
        if (page.channels.isNotEmpty()) return page
        val channels = runCatching { Parser.channels(call("browse", JSONObject().put("browseId", "FEchannels"))) }
            .getOrDefault(emptyList())
        return page.copy(channels = channels)
    }

    fun history(): FeedPage = browse("FEhistory")

    fun likedVideos(): FeedPage = browse("VLLL")

    fun watchLater(): FeedPage = browse("VLWL")

    fun browse(browseId: String, params: String? = null): FeedPage =
        Parser.feed(call("browse", JSONObject().put("browseId", browseId).apply { params?.let { put("params", it) } }))

    fun channel(channelId: String): ChannelPage =
        Parser.channel(call("browse", JSONObject().put("browseId", channelId)))

    /** Next page of any feed returned by this client. */
    fun continuation(token: String, endpoint: String = "browse"): FeedPage =
        Parser.feed(call(endpoint, JSONObject().put("continuation", token)))

    fun search(query: String): FeedPage = Parser.feed(call("search", JSONObject().put("query", query)))

    fun searchContinuation(token: String): FeedPage = continuation(token, "search")

    /**
     * Comment sort options ("Top comments" / "Newest first") as continuation tokens. Comes from the
     * web client's comments header; the tokens are then paged through NewPipeExtractor.
     */
    fun commentSorts(videoId: String): List<dev.materialtube.data.CommentSort> {
        val next = call("next", JSONObject().put("videoId", videoId), WEB, withAuth = false)
        val token = Parser.commentsToken(next) ?: return emptyList()
        val header = call("next", JSONObject().put("continuation", token), WEB, withAuth = false)
        return Parser.commentSorts(header)
    }

    fun next(videoId: String): NextInfo =
        Parser.next(call("next", JSONObject().put("videoId", videoId)), videoId)

    /**
     * Player request. Streams come from NewPipeExtractor; this call is for the video details and,
     * more importantly, the watch-history tracking URLs.
     *
     * The TV client's player endpoint currently answers "The page needs to be reloaded", so we
     * try a few clients (signed in first, then anonymously) until one returns tracking URLs.
     * Watch-time pings are then sent signed in, so they still count for the account.
     */
    fun player(videoId: String): PlayerInfo {
        var fallback: PlayerInfo? = null
        val signedIn = auth?.isSignedIn == true
        val attempts = PLAYER_CLIENTS.flatMap { c -> if (signedIn) listOf(c to true, c to false) else listOf(c to false) }
        for ((client, withAuth) in attempts) {
            val body = JSONObject()
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            val info = runCatching {
                Parser.player(call("player", body, client, withAuth))
            }.getOrNull()?.let { it.copy(tracking = it.tracking?.copy(clientName = client.name, clientVersion = client.version)) }
                ?: continue
            if (info.tracking?.playbackUrl != null && info.details != null) return info
            if (fallback == null || fallback.details == null) fallback = info
        }
        return fallback ?: PlayerInfo(null, null, null)
    }

    fun like(videoId: String) = action("like/like", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun dislike(videoId: String) = action("like/dislike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun removeLike(videoId: String) = action("like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun subscribe(channelId: String) = action("subscription/subscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))

    fun unsubscribe(channelId: String) = action("subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))

    private fun action(endpoint: String, body: JSONObject) {
        if (auth?.isSignedIn != true) throw InnerTubeException("Sign in first")
        call(endpoint, body)
    }

    fun context(client: Client = TV): JSONObject {
        val l = locale()
        val c = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", l.language.ifEmpty { "en" })
            .put("gl", l.country.ifEmpty { "US" })
            .put("userAgent", client.userAgent)
        client.extra.forEach { (k, v) -> c.put(k, v) }
        return JSONObject().put("client", c).put("user", JSONObject().put("lockedSafetyMode", false))
    }

    fun call(endpoint: String, body: JSONObject, client: Client = TV, withAuth: Boolean = true): JSONObject {
        body.put("context", context(client))
        val token = if (withAuth) auth?.accessToken() else null
        return try {
            execute(endpoint, body, client, token)
        } catch (e: InnerTubeException) {
            // Access token revoked/expired early: refresh once and retry.
            if (e.code != 401 || token == null || auth == null) throw e
            execute(endpoint, body, client, auth.accessToken(forceRefresh = true))
        }
    }

    private fun execute(endpoint: String, body: JSONObject, client: Client, token: String?): JSONObject {
        var lastError: java.io.IOException? = null
        for (i in hosts.indices) {
            val index = (workingHost + i) % hosts.size
            try {
                return execute(hosts[index], endpoint, body, client, token).also { workingHost = index }
            } catch (e: java.io.IOException) {
                if (e is InnerTubeException || !e.isUnreachable()) throw e
                lastError = e
            }
        }
        throw lastError ?: InnerTubeException("No InnerTube host reachable")
    }

    private fun execute(host: String, endpoint: String, body: JSONObject, client: Client, token: String?): JSONObject {
        val request = Request.Builder()
            .url("https://$host/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", client.userAgent)
            .header("Origin", "https://www.youtube.com")
            .header("X-YouTube-Client-Name", client.id)
            .header("X-YouTube-Client-Version", client.version)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(text).obj("error")?.str("message") }.getOrNull()
                throw InnerTubeException("$endpoint: HTTP ${response.code}${msg?.let { " – $it" } ?: ""}", response.code)
            }
            return JSONObject(text)
        }
    }

    /** An InnerTube client identity. */
    data class Client(
        val name: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any> = emptyMap(),
    )

    companion object {
        /** youtubei.googleapis.com serves the same API (it's what the official Android app uses). */
        val HOSTS = listOf("www.youtube.com", "youtubei.googleapis.com")
        const val CLIENT_NAME = "TVHTML5"
        const val CLIENT_VERSION = "7.20261007.13.00"
        private val JSON = "application/json".toMediaType()

        /** The TV app: the client the OAuth token belongs to; used for feeds and actions. */
        val TV = Client(CLIENT_NAME, "7", CLIENT_VERSION, TvOAuth.TV_USER_AGENT)

        val WEB = Client(
            "WEB", "1", "2.20251010.00.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        )

        /** Clients that currently return playback tracking from the player endpoint. */
        val PLAYER_CLIENTS = listOf(
            TV,
            Client(
                "MWEB", "2", "2.20251010.01.00",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36",
            ),
            Client(
                "IOS", "5", "20.10.4",
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
            ),
            Client(
                "ANDROID_VR", "28", "1.65.10",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                mapOf("androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
            ),
        )
    }
}
