package dev.ytplus.data.innertube

import dev.ytplus.data.ChannelPage
import dev.ytplus.data.FeedPage
import dev.ytplus.data.NextInfo
import dev.ytplus.data.PlayerInfo
import dev.ytplus.data.auth.TvOAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
) {

    fun home(): FeedPage {
        // The TV app calls its home "default"; fall back to the web id if that ever goes away.
        val page = Parser.feed(call("browse", JSONObject().put("browseId", "default")))
        if (page.shelves.isNotEmpty()) return page
        return Parser.feed(call("browse", JSONObject().put("browseId", "FEwhat_to_watch")))
    }

    fun subscriptions(): FeedPage = browse("FEsubscriptions")

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

    fun next(videoId: String): NextInfo =
        Parser.next(call("next", JSONObject().put("videoId", videoId)), videoId)

    /**
     * Signed-in player request. Streams come from NewPipeExtractor; this call is for the video
     * details and, more importantly, the account-bound watch-history tracking URLs.
     */
    fun player(videoId: String): PlayerInfo =
        Parser.player(
            call(
                "player",
                JSONObject()
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true),
            ),
        )

    fun like(videoId: String) = action("like/like", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun dislike(videoId: String) = action("like/dislike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun removeLike(videoId: String) = action("like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun subscribe(channelId: String) = action("subscription/subscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))

    fun unsubscribe(channelId: String) = action("subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))

    private fun action(endpoint: String, body: JSONObject) {
        if (auth?.isSignedIn != true) throw InnerTubeException("Sign in first")
        call(endpoint, body)
    }

    fun context(): JSONObject {
        val l = locale()
        return JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", CLIENT_NAME)
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", l.language.ifEmpty { "en" })
                .put("gl", l.country.ifEmpty { "US" })
                .put("platform", "TV")
                .put("userAgent", TvOAuth.TV_USER_AGENT),
        ).put("user", JSONObject().put("lockedSafetyMode", false))
    }

    fun call(endpoint: String, body: JSONObject): JSONObject {
        body.put("context", context())
        return try {
            execute(endpoint, body, auth?.accessToken())
        } catch (e: InnerTubeException) {
            // Access token revoked/expired early: refresh once and retry.
            if (e.code != 401 || auth?.isSignedIn != true) throw e
            execute(endpoint, body, auth.accessToken(forceRefresh = true))
        }
    }

    private fun execute(endpoint: String, body: JSONObject, token: String?): JSONObject {
        val request = Request.Builder()
            .url("$BASE_URL/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", TvOAuth.TV_USER_AGENT)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/tv")
            .header("X-YouTube-Client-Name", CLIENT_NAME_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
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

    companion object {
        const val BASE_URL = "https://www.youtube.com/youtubei/v1"
        const val CLIENT_NAME = "TVHTML5"
        const val CLIENT_NAME_ID = "7"
        const val CLIENT_VERSION = "7.20250923.13.00"
        private val JSON = "application/json".toMediaType()
    }
}
