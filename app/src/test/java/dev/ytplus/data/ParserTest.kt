package dev.ytplus.data

import dev.ytplus.data.innertube.Parser
import dev.ytplus.data.sponsorblock.SponsorBlock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    private fun fixture(name: String) =
        JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test
    fun parsesTvHomeShelves() {
        val page = Parser.feed(fixture("tv_home.json"))
        assertEquals(listOf("Recommended", "Lockups"), page.shelves.map { it.title })
        assertEquals("PAGE_TOKEN", page.continuation)

        val first = page.shelves[0].items
        assertEquals(listOf("abc123def45", "live0000001"), first.map { it.id }) // channel tile skipped
        with(first[0]) {
            assertEquals("First video", title)
            assertEquals("Some Channel", channel)
            assertEquals("UCchannel000000000000000", channelId)
            assertEquals("1.2M views • 3 days ago", meta)
            assertEquals("12:34", duration)
            assertEquals("https://i.ytimg.com/a_big.jpg", thumbnail)
            assertEquals(40, watchedPercent)
            assertFalse(isLive)
        }
        assertTrue(first[1].isLive)
        assertNull(first[1].duration)

        with(page.shelves[1].items.single()) {
            assertEquals("lockup00001", id)
            assertEquals("Lockup video", title)
            assertEquals("Lockup Channel", channel)
            assertEquals("5K views • 1 year ago", meta)
            assertEquals("1:02:03", duration)
        }
    }

    @Test
    fun parsesNext() {
        val next = Parser.next(fixture("tv_next.json"), "current0001")
        assertEquals(listOf("related0001"), next.related.map { it.id })
        assertEquals(LikeStatus.LIKE, next.likeStatus)
        assertEquals(true, next.subscribed)
        assertEquals("UCowner000000000000000", next.channelId)
        assertEquals("Owner Name", next.channelName)
        assertEquals("1.5K", next.likeCountText)
    }

    @Test
    fun parsesClassicVideoRenderer() {
        val json = JSONObject(
            """{"contents":[{"videoRenderer":{"videoId":"classic0001","title":{"runs":[{"text":"Classic"}]},
               "longBylineText":{"runs":[{"text":"Chan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCchan"}}}]},
               "shortViewCountText":{"simpleText":"10 views"},"publishedTimeText":{"simpleText":"1 hour ago"},
               "lengthText":{"simpleText":"4:20"}}},
               {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}]}""",
        )
        val page = Parser.feed(json)
        val v = page.shelves.single().items.single()
        assertEquals("Classic", v.title)
        assertEquals("UCchan", v.channelId)
        assertEquals("10 views • 1 hour ago", v.meta)
        assertEquals("4:20", v.duration)
        assertEquals("NEXT", page.continuation)
    }

    @Test
    fun parsesPlayer() {
        val info = Parser.player(
            JSONObject(
                """{"playabilityStatus":{"status":"OK"},
                   "videoDetails":{"videoId":"v1","title":"T","author":"A","channelId":"UC1","lengthSeconds":"61","viewCount":"99","shortDescription":"d"},
                   "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?docid=v1"},
                                       "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?docid=v1"}}}""",
            ),
        )
        assertEquals("OK", info.playabilityStatus)
        assertEquals(61L, info.details?.lengthSeconds)
        assertEquals("https://s.youtube.com/api/stats/watchtime?docid=v1", info.tracking?.watchtimeUrl)
    }

    @Test
    fun parsesSponsorBlock() {
        val segs = SponsorBlock.parse(
            """[{"segment":[30.5,60.0],"category":"sponsor","UUID":"u2","actionType":"skip"},
                {"segment":[1.0,5.0],"category":"intro","UUID":"u1"},
                {"segment":[0,0],"category":"sponsor","UUID":"full","actionType":"full"}]""",
        )
        assertEquals(listOf("u1", "u2"), segs.map { it.uuid })
    }
}
