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

    @Test
    fun parsesCommunityPosts() {
        val json = JSONObject(
            """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{
                  "postId":"Ugkx1","authorText":{"runs":[{"text":"Bog"}]},
                  "authorThumbnail":{"thumbnails":[{"url":"//yt3/bog","width":48}]},
                  "contentText":{"runs":[{"text":"New video "},{"text":"out now"}]},
                  "publishedTimeText":{"runs":[{"text":"2 days ago"}]},"voteCount":{"simpleText":"1.2K"},
                  "actionButtons":{"commentActionButtonsRenderer":{"replyButton":{"buttonRenderer":{"text":{"simpleText":"85"}}}}},
                  "backstageAttachment":{"postMultiImageRenderer":{"images":[
                    {"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://img/1","width":640}]}}},
                    {"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://img/2","width":640}]}}}]}}}}}},
                {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{
                  "postId":"Ugkx2","contentText":{"runs":[{"text":"Which one?"}]},"voteCount":{"simpleText":"0"},
                  "backstageAttachment":{"pollRenderer":{"choices":[{"text":{"runs":[{"text":"A"}]}},{"text":{"runs":[{"text":"B"}]}}]}}}}}},
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"MORE"}}}}
              ]}}]}}}}]}}}
            """,
        )
        val page = Parser.posts(json)
        assertEquals(listOf("Ugkx1", "Ugkx2"), page.posts.map { it.id })
        with(page.posts[0]) {
            assertEquals("Bog", author)
            assertEquals("https://yt3/bog", authorAvatar)
            assertEquals("New video out now", text)
            assertEquals("2 days ago", published)
            assertEquals("1.2K", likes)
            assertEquals("85", comments)
            assertEquals(listOf("https://img/1", "https://img/2"), images)
        }
        assertEquals(listOf("A", "B"), page.posts[1].poll)
        assertNull(page.posts[1].likes)
        assertEquals("MORE", page.continuation)
    }

    @Test
    fun parsesAboutPanel() {
        val json = JSONObject(
            """
            {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[{"aboutChannelRenderer":{"metadata":{
              "aboutChannelViewModel":{"description":"Hi there","subscriberCountText":"1M subscribers",
                "videoCountText":"900 videos","viewCountText":"1,000 views","joinedDateText":{"content":"Joined Jan 1, 2012"},
                "country":"Canada","links":[{"channelExternalLinkViewModel":{"title":{"content":"Shop"},"link":{"content":"shop.example.com"}}}]}}}}]}}]}
            """,
        )
        val about = Parser.about(json, "fallback")
        assertEquals("Hi there", about.description)
        assertEquals(listOf("1M subscribers", "900 videos", "1,000 views", "Joined Jan 1, 2012", "Canada"), about.stats)
        assertEquals(listOf(ChannelLink("Shop", "https://shop.example.com")), about.links)
        assertEquals("fallback", Parser.about(null, "fallback").description)
    }

    @Test
    fun parsesPostCommentEntities() {
        val json = JSONObject(
            """
            {"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[
              {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"K2"}},
                "replies":{"commentRepliesRenderer":{"contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"REPLIES2"}}}}]}}}},
              {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"K1"}}}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}
            ]}}],
            "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
              {"entityKey":"K1","payload":{"commentEntityPayload":{"properties":{"commentId":"c1","content":{"content":"First!"},"publishedTime":"1 day ago"},
                "author":{"displayName":"@a","avatarThumbnailUrl":"https://a"},"toolbar":{"likeCountNotliked":"12","replyCount":""}}}},
              {"entityKey":"K2","payload":{"commentEntityPayload":{"properties":{"commentId":"c2","content":{"content":"Nice"},"publishedTime":"2 days ago"},
                "author":{"displayName":"@b","isCreator":true},"toolbar":{"likeCountNotliked":" ","replyCount":"3"}}}}
            ]}}}
            """,
        )
        val page = Parser.postComments(json)
        assertEquals(listOf("c2", "c1"), page.comments.map { it.id }) // thread order, not mutation order
        with(page.comments[0]) {
            assertEquals("@b", author)
            assertEquals(3, replyCount)
            assertEquals("REPLIES2", repliesToken)
            assertTrue(byUploader)
            assertNull(likes)
        }
        assertEquals("12", page.comments[1].likes)
        assertEquals("NEXT", page.next)
    }

    @Test
    fun postHasCommentAndLikeEndpoints() {
        val json = JSONObject(
            """
            {"backstagePostRenderer":{"postId":"P1","contentText":{"simpleText":"hi"},
              "actionButtons":{"commentActionButtonsRenderer":{
                "likeButton":{"toggleButtonRenderer":{"isToggled":false,
                  "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"LIKE_TOKEN"}},
                  "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNLIKE_TOKEN"}}}},
                "replyButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"FEpost_detail","params":"PARAMS"}}}}}}}}
            """,
        )
        val post = Parser.posts(json).posts.single()
        assertEquals("FEpost_detail", post.detailBrowseId)
        assertEquals("PARAMS", post.detailParams)
        assertEquals("LIKE_TOKEN", post.likeAction)
        assertEquals("UNLIKE_TOKEN", post.unlikeAction)
        assertFalse(post.liked)
    }

    @Test
    fun mixerUsesPersonalShortsBeforeSearch() {
        fun v(id: String, short: Boolean = false) = VideoItem(id, id, isShort = short)
        var searched = 0
        var personalPages = 0
        val mixer = dev.materialtube.data.innertube.ShortsMixer(
            search = { searched++; FeedPage(listOf(Shelf(null, List(12) { v("q$searched-$it", true) })), null) },
            more = { FeedPage(emptyList(), null) },
            personal = { personalPages++; FeedPage(listOf(Shelf("Shorts", List(10) { v("p0-$it", true) })), "P1") },
            personalMore = { t -> personalPages++; FeedPage(listOf(Shelf("Shorts", List(10) { v("p$t-$it", true) })), null) },
        )
        val home = FeedPage(List(4) { i -> Shelf("Shelf $i", List(8) { j -> v("v$i-$j") }) }, null)
        val rows = mixer.mix(home).shelves.filter { it.title == "Shorts" }
        assertEquals(4, rows.size)
        // The first two rows come from the account's own Shorts, only then from searches.
        assertTrue(rows[0].items.all { it.id.startsWith("p0-") })
        assertTrue(rows[1].items.all { it.id.startsWith("pP1-") })
        assertTrue(rows[2].items.all { it.id.startsWith("q") })
        assertEquals(2, personalPages)
    }

    @Test
    fun parsesReelSequence() {
        val json = JSONObject(
            """
            {"entries":[
              {"command":{"reelWatchEndpoint":{"videoId":"short000001","thumbnail":{"thumbnails":[{"url":"https://t/1","width":405}]}}}},
              {"command":{"reelWatchEndpoint":{"videoId":"short000002"}}}],
             "continuationEndpoint":{"continuationCommand":{"token":"SEQ2"}}}
            """,
        )
        val page = Parser.reelSequence(json)
        val items = page.shelves.single().items
        assertEquals(listOf("short000001", "short000002"), items.map { it.id })
        assertTrue(items.all { it.isShort })
        assertEquals("https://t/1", items[0].thumbnail)
        assertEquals("SEQ2", page.continuation)
    }
}
