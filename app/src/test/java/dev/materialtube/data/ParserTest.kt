package dev.materialtube.data

import dev.materialtube.data.innertube.Parser
import dev.materialtube.data.sponsorblock.SponsorBlock
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

    @Test
    fun parsesChannelsAndShorts() {
        val json = JSONObject(
            """{"contents":[
                {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_CHANNEL","contentId":"UCabc",
                  "header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3/a.jpg","width":88}]}}},
                  "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Chan A"},"lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"1M subscribers"}}}]}}]}},
                  "onSelectCommand":{"browseEndpoint":{"browseId":"UCabc"}}}},
                {"tabRenderer":{"title":"Chan B","thumbnail":{"thumbnails":[{"url":"https://yt3/b.jpg","width":88}]},
                  "endpoint":{"browseEndpoint":{"browseId":"UCbbb"}}}},
                {"tabRenderer":{"title":"Home","endpoint":{"browseEndpoint":{"browseId":"FEwhat_to_watch"}}}},
                {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_SHORTS","contentId":"short000001",
                  "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A short"}}},
                  "onSelectCommand":{"reelWatchEndpoint":{"videoId":"short000001"}}}},
                {"shortsLockupViewModel":{"entityId":"shorts-shelf-item-short000002",
                  "overlayMetadata":{"primaryText":{"content":"Web short"},"secondaryText":{"content":"2M views"}},
                  "onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"short000002"}}}}}
            ]}""",
        )
        val page = Parser.feed(json)
        assertEquals(listOf("UCabc", "UCbbb"), page.channels.map { it.id })
        assertEquals("1M subscribers", page.channels[0].subtitle)
        assertEquals("https://yt3/b.jpg", page.channels[1].avatar)
        val shorts = page.shelves.flatMap { it.items }
        assertEquals(listOf("short000001", "short000002"), shorts.map { it.id })
        assertTrue(shorts.all { it.isShort })
    }

    @Test
    fun parsesCommentSorts() {
        val json = JSONObject(
            """{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[{"commentsHeaderRenderer":{
                "sortMenu":{"sortFilterSubMenuRenderer":{"subMenuItems":[
                  {"title":"Top","serviceEndpoint":{"continuationCommand":{"token":"TOP"}}},
                  {"title":"Newest","serviceEndpoint":{"continuationCommand":{"token":"NEW"}}}]}}}}]}}]}""",
        )
        assertEquals(listOf(CommentSort("Top", "TOP"), CommentSort("Newest", "NEW")), Parser.commentSorts(json))
    }

    @Test
    fun mixesShortsRowsRegularly() {
        fun v(id: String, short: Boolean = false) = VideoItem(id, id, isShort = short)
        var n = 0
        val shortsPage = { FeedPage(listOf(Shelf(null, List(12) { v("s${n++}", true) })), "more") }
        val mixer = dev.materialtube.data.innertube.ShortsMixer(search = { shortsPage() }, more = { shortsPage() })
        val home = FeedPage(List(6) { i -> Shelf("Shelf $i", List(5) { j -> v("v$i-$j") }) }, null)
        val mixed = mixer.mix(home)
        val titles = mixed.shelves.map { it.title }
        // a Shorts row after every ~8 videos (every 2 shelves of 5)
        assertEquals(listOf("Shelf 0", "Shelf 1", "Shorts", "Shelf 2", "Shelf 3", "Shorts", "Shelf 4", "Shelf 5", "Shorts"), titles)
        val ids = mixed.shelves.filter { it.title == "Shorts" }.flatMap { it.items.map { s -> s.id } }
        assertEquals(ids.size, ids.toSet().size) // no repeats
    }

    @Test
    fun subscriptionTabsAreChannelsButAllIsNot() {
        val json = JSONObject(
            """
            {"contents":{"tvBrowseRenderer":{"content":{"tvSecondaryNavRenderer":{"sections":[{"tvSecondaryNavSectionRenderer":{"tabs":[
              {"tabRenderer":{"title":"All","endpoint":{"browseEndpoint":{"browseId":"FEsubscriptions"}},
                "content":{"tvSurfaceContentRenderer":{"content":{"gridRenderer":{"items":[
                  {"tileRenderer":{"contentId":"vid00000001","contentType":"TILE_CONTENT_TYPE_VIDEO",
                    "onSelectCommand":{"watchEndpoint":{"videoId":"vid00000001"}},
                    "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A video"}}},
                    "menu":{"browseEndpoint":{"browseId":"UCvideochannel0000000000"}}}}]}}}}}},
              {"tabRenderer":{"title":"Bog","endpoint":{"browseEndpoint":{"browseId":"UCbog000000000000000000"}},
                "thumbnail":{"thumbnails":[{"url":"https://yt3/bog=s88-c","width":88,"height":88}]}}}
            ]}}]}}}}}
            """,
        )
        val channels = Parser.channels(json)
        assertEquals(listOf("UCbog000000000000000000"), channels.map { it.id })
        assertEquals("Bog", channels[0].title)
    }

    @Test
    fun parsesWebGuideSubscriptions() {
        val json = JSONObject(
            """
            {"items":[{"guideSubscriptionsSectionRenderer":{"items":[
              {"guideEntryRenderer":{"formattedTitle":{"simpleText":"Bog"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCbog000000000000000000"}},
                "thumbnail":{"thumbnails":[{"url":"https://yt3/bog","width":88,"height":88}]}}},
              {"guideEntryRenderer":{"formattedTitle":{"simpleText":"History"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"FEhistory"}}}}
            ]}}]}
            """,
        )
        assertEquals(listOf("Bog"), Parser.channels(json).map { it.title })
    }
}
