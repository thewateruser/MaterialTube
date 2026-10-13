package dev.materialtube.data

import dev.materialtube.data.auth.InMemoryTokenStore
import dev.materialtube.data.auth.TvOAuth
import dev.materialtube.data.innertube.InnerTube
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Hits the real YouTube endpoints anonymously to catch API/layout changes. Runs only when
 * MATERIALTUBE_LIVE_TEST=1 (CI sets it in a separate, non-blocking step).
 */
class LiveApiTest {
    private val http = OkHttpClient()
    private val auth = TvOAuth(http, InMemoryTokenStore())
    private val api = InnerTube(http, auth)

    @Before
    fun onlyWhenEnabled() = assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")

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
        assertTrue(items.size > 20)
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
        println("player: status=${info.playabilityStatus} title=${info.details?.title} tracking=${info.tracking}")
        assertTrue(info.details != null)
        assertTrue(info.tracking?.playbackUrl != null)
    }

    @Test
    fun channelHasTitleAndVideos() {
        val page = api.channel("UCuAXFkgsw1L7xaCfnd5JJOw")
        println("channel: ${page.title} shelves=${page.feed.shelves.map { it.items.size }}")
        assertTrue(!page.title.isNullOrBlank())
        assertTrue(page.feed.shelves.isNotEmpty())
    }

    /** The fallback hosts used when www.youtube.com can't be resolved must work on their own. */
    @Test
    fun fallbackHostsWork() {
        val googleapisOnly = TvOAuth(http, InMemoryTokenStore(), oauthBases = listOf("https://oauth2.googleapis.com"))
        val code = googleapisOnly.startDeviceFlow()
        println("oauth2.googleapis.com device flow: ${code.userCode}")
        val api = InnerTube(http, null, hosts = listOf("youtubei.googleapis.com"))
        val items = api.search("lofi hip hop").shelves.flatMap { it.items }
        println("youtubei.googleapis.com search: ${items.size}")
        assertTrue(items.size > 20)
        val dead = InnerTube(http, null, hosts = listOf("nonexistent.invalid", "youtubei.googleapis.com"))
        assertTrue(dead.search("music").shelves.isNotEmpty())
    }

    @Test
    fun dnsOverHttpsResolves() {
        val dns = dev.materialtube.data.net.FallbackDns(OkHttpClient())
        val addresses = dns.lookup("www.youtube.com")
        println("FallbackDns: $addresses")
        assertTrue(addresses.isNotEmpty())
    }

    @Test
    fun searchFindsChannels() {
        val page = api.search("mrbeast")
        println("search channels: ${page.channels.take(5)}")
        assertTrue(page.channels.isNotEmpty())
    }

    @Test
    fun commentSortOptions() {
        val sorts = api.commentSorts("dQw4w9WgXcQ")
        println("comment sorts: ${sorts.map { it.label }}")
        assertTrue(sorts.size >= 2)
    }

    @Test
    fun shortsShelfAvailable() {
        val shelf = api.shortsShelf()
        println("shorts shelf: ${shelf?.items?.size} first=${shelf?.items?.firstOrNull()}")
        assertTrue(shelf != null && shelf.items.isNotEmpty())
    }

    @Test
    fun shortsMixedThroughLongFeed() {
        val mixer = dev.materialtube.data.innertube.ShortsMixer(
            search = { api.shortsSearch(it) },
            more = { api.shortsSearchMore(it) },
        )
        fun fakePage(n: Int) = FeedPage(
            (0 until 4).map { s -> Shelf("Shelf $s", (0 until 12).map { VideoItem(id = "v$n-$s-$it", title = "Video") }) },
            "next",
        )
        val pages = (0 until 4).map { mixer.mix(fakePage(it)) }
        val rows = pages.map { p -> p.shelves.count { s -> s.items.isNotEmpty() && s.items.all { it.isShort } } }
        println("shorts rows per page: $rows")
        assertTrue(rows.all { it >= 3 })
    }

    /** Diagnostics for the personal Shorts sources (signed out here, so only checks plumbing). */
    @Test
    fun personalShortsSources() {
        val seq = runCatching { api.reelSequence(withAuth = false) }
        println("REEL SEQUENCE: ${seq.exceptionOrNull()?.message ?: "items=${seq.getOrNull()?.shelves?.sumOf { it.items.size }} next=${seq.getOrNull()?.continuation != null} first=${seq.getOrNull()?.shelves?.firstOrNull()?.items?.firstOrNull()}"}")
        seq.getOrNull()?.continuation?.let { t ->
            val more = runCatching { api.reelSequenceMore(t, withAuth = false) }
            println("REEL MORE: ${more.exceptionOrNull()?.message ?: "items=${more.getOrNull()?.shelves?.sumOf { it.items.size }}"}")
        }
    }
}
