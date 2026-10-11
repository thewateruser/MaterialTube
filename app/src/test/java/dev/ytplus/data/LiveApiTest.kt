package dev.ytplus.data

import dev.ytplus.data.auth.InMemoryTokenStore
import dev.ytplus.data.auth.TvOAuth
import dev.ytplus.data.innertube.InnerTube
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Hits the real YouTube endpoints anonymously to catch API/layout changes. Runs only when
 * YTPLUS_LIVE_TEST=1 (CI sets it in a separate, non-blocking step).
 */
class LiveApiTest {
    private val http = OkHttpClient()
    private val auth = TvOAuth(http, InMemoryTokenStore())
    private val api = InnerTube(http, auth)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("YTPLUS_LIVE_TEST") == "1")

    /** Prints which renderers a response contains, to adapt the parser when YouTube changes layouts. */
    private fun shape(label: String, json: JSONObject) {
        val counts = sortedMapOf<String, Int>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> n.keys().forEach { k ->
                    if (k.endsWith("Renderer") || k.endsWith("ViewModel") || k.endsWith("Continuation")) counts[k] = (counts[k] ?: 0) + 1
                    walk(n.opt(k))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        println("shape[$label] top=${json.keys().asSequence().toList()} renderers=$counts")
    }

    private fun sample(label: String, json: JSONObject, key: String) {
        var found: JSONObject? = null
        fun walk(n: Any?) {
            if (found != null) return
            when (n) {
                is JSONObject -> { if (n.has(key)) found = n.getJSONObject(key) else n.keys().forEach { walk(n.opt(it)) } }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        println("sample[$label/$key]=${found?.toString()?.take(3000)}")
    }

    @Test
    fun dumpShapes() {
        val home = api.call("browse", JSONObject().put("browseId", "default"))
        shape("home-default", home)
        shape("home-FEwhat_to_watch", api.call("browse", JSONObject().put("browseId", "FEwhat_to_watch")))
        val search = api.call("search", JSONObject().put("query", "lofi hip hop"))
        shape("search", search)
        sample("search", search, "tileRenderer")
        sample("search", search, "lockupViewModel")
        val player = api.call("player", JSONObject().put("videoId", "dQw4w9WgXcQ"))
        println("player playability=${player.optJSONObject("playabilityStatus")} keys=${player.keys().asSequence().toList()}")
        val channel = api.call("browse", JSONObject().put("browseId", "UCuAXFkgsw1L7xaCfnd5JJOw"))
        shape("channel", channel)
        println("channel parsed: " + dev.ytplus.data.innertube.Parser.channel(channel).let { "${it.title} ${it.feed.shelves.map { s -> s.items.size }}" })
    }

    @Test
    fun dumpDetails() {
        val search = api.call("search", JSONObject().put("query", "lofi hip hop"))
        val types = sortedMapOf<String, Int>()
        var printed = 0
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("lockupViewModel")?.let { l ->
                        val key = "${l.optString("contentType")}|id=${l.has("contentId")}|parsed=${dev.ytplus.data.innertube.Parser.video(n) != null}"
                        types[key] = (types[key] ?: 0) + 1
                        if (printed < 2 && l.optString("contentType").contains("VIDEO")) {
                            printed++
                            println("lockup.contentImage=${l.optJSONObject("contentImage")?.toString()?.take(2500)}")
                            println("lockup.keys=${l.keys().asSequence().toList()}")
                        }
                    }
                    n.keys().forEach { walk(n.opt(it)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(search)
        println("search lockups: $types")
        println("search parsed shelves: " + dev.ytplus.data.innertube.Parser.feed(search).shelves.map { "${it.title}:${it.items.size}" })

        val channel = api.call("browse", JSONObject().put("browseId", "UCuAXFkgsw1L7xaCfnd5JJOw"))
        sample("channel", channel, "channelHeaderRenderer")

        // Player variants: find what TVHTML5 needs to return a playable response + tracking URLs.
        val tvHtml = http.newCall(okhttp3.Request.Builder().url("https://www.youtube.com/tv")
            .header("User-Agent", TvOAuth.TV_USER_AGENT).build()).execute().use { it.body!!.string() }
        val cver = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(tvHtml)?.groupValues?.get(1)
        val cverAlt = Regex("clientVersion\\?\":\\?\"(7\\.[0-9.]+)").find(tvHtml)?.groupValues?.get(1)
        val baseJs = Regex("src=\"([^\"]*base\\.js)\"").find(tvHtml)?.groupValues?.get(1)
            ?: Regex("\"jsUrl\":\"([^\"]+)\"").find(tvHtml)?.groupValues?.get(1)
        println("tv html: len=${tvHtml.length} cver=$cver cverAlt=$cverAlt baseJs=$baseJs scripts=${Regex("<script[^>]*src=\"([^\"]+)\"").findAll(tvHtml).map { it.groupValues[1] }.toList().take(8)}")
        var sts: Int? = null
        if (baseJs != null) {
            val js = http.newCall(okhttp3.Request.Builder().url(if (baseJs.startsWith("http")) baseJs else "https://www.youtube.com$baseJs")
                .header("User-Agent", TvOAuth.TV_USER_AGENT).build()).execute().use { it.body!!.string() }
            sts = Regex("(?:signatureTimestamp|sts)[:=](\\d{5})").find(js)?.groupValues?.get(1)?.toInt()
            println("base.js len=${js.length} sts=$sts clientId=${Regex("clientId:\"([^\"]+)\"").find(js)?.groupValues?.get(1)}")
        }
        val visitor = api.call("browse", JSONObject().put("browseId", "default"))
            .optJSONObject("responseContext")?.optString("visitorData")
        println("visitorData=${visitor?.take(20)}")
        data class C(val name: String, val id: String, val version: String, val ua: String, val extra: (JSONObject) -> Unit = {})
        val clients = listOf(
            C("TVHTML5", "7", cver ?: InnerTube.CLIENT_VERSION, TvOAuth.TV_USER_AGENT),
            C("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "85", "2.0", TvOAuth.TV_USER_AGENT) {
                it.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
            },
            C("WEB", "1", "2.20251010.00.00", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"),
            C("MWEB", "2", "2.20251010.01.00", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"),
            C("ANDROID_VR", "28", "1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"),
            C("IOS", "5", "20.10.4", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"),
            C("WEB_EMBEDDED_PLAYER", "56", "1.20251010.01.00", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0") {
                it.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
            },
        )
        for (c in clients) for (withVisitor in listOf(false, true)) {
            if (withVisitor && visitor.isNullOrEmpty()) continue
            val client = JSONObject().put("clientName", c.name).put("clientVersion", c.version).put("hl", "en").put("gl", "US")
            if (c.name == "ANDROID_VR") client.put("androidSdkVersion", 32).put("osName", "Android").put("osVersion", "12L")
            if (c.name == "IOS") client.put("deviceMake", "Apple").put("deviceModel", "iPhone16,2").put("osName", "iPhone").put("osVersion", "18.3.2.22D82")
            if (withVisitor) client.put("visitorData", visitor)
            val ctx = JSONObject().put("client", client)
            c.extra(ctx)
            val body = JSONObject().put("videoId", "dQw4w9WgXcQ").put("context", ctx).put("contentCheckOk", true).put("racyCheckOk", true)
            if (sts != null) body.put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", sts)))
            val req = okhttp3.Request.Builder().url("${InnerTube.BASE_URL}/player?prettyPrint=false")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), body.toString()))
                .header("User-Agent", c.ua)
                .header("Origin", "https://www.youtube.com")
                .header("X-YouTube-Client-Name", c.id)
                .header("X-YouTube-Client-Version", c.version)
                .apply { if (withVisitor) header("X-Goog-Visitor-Id", visitor!!) }
                .build()
            val r = runCatching { http.newCall(req).execute().use { JSONObject(it.body!!.string()) } }.getOrElse { JSONObject().put("err", it.toString()) }
            val ps = r.optJSONObject("playabilityStatus")
            val track = r.optJSONObject("playbackTracking")?.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")
            println("player[${c.name}${if (withVisitor) "+visitor" else ""}] status=${ps?.optString("status")} reason=${ps?.optString("reason")?.take(80)} title=${r.optJSONObject("videoDetails")?.optString("title")} tracking=${track?.take(60)} streaming=${r.has("streamingData")} err=${r.optString("err")}")
        }
    }

    @Test
    fun deviceCodeFlowStarts() {
        val code = auth.startDeviceFlow()
        println("device flow ok: user_code=${code.userCode} url=${code.verificationUrl}")
        assertTrue(code.userCode.isNotBlank())
    }

    @Test
    fun searchReturnsVideos() {
        val page = api.search("lofi hip hop")
        val items = page.shelves.flatMap { it.items }
        println("search: ${items.size} videos, first=${items.firstOrNull()}")
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun homeReturnsShelves() {
        val page = api.home()
        // Signed out, YouTube only returns a "sign in" nudge here; just check the call works.
        println("home (signed out): ${page.shelves.map { "${it.title}(${it.items.size})" }} cont=${page.continuation != null}")
    }

    @Test
    fun nextReturnsRelated() {
        val next = api.next("dQw4w9WgXcQ")
        println("next: related=${next.related.size} channel=${next.channelName}/${next.channelId}")
        assertTrue(next.related.isNotEmpty())
    }

    @Test
    fun playerReturnsDetails() {
        val info = api.player("dQw4w9WgXcQ")
        println("player: status=${info.playabilityStatus} title=${info.details?.title} tracking=${info.tracking != null}")
        assertTrue(info.details != null)
    }
}
