package dev.materialtube.data

import dev.materialtube.data.innertube.MusicParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicParserTest {

    @Test
    fun parsesHomeShelves() {
        val json = JSONObject(
            """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
              "contents":[
                {"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Quick picks"}]}}},
                  "contents":[
                    {"musicResponsiveListItemRenderer":{
                      "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3/song=w60-h60-l90","width":60}]}}},
                      "playlistItemData":{"videoId":"song0000001"},
                      "flexColumns":[
                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song One",
                          "navigationEndpoint":{"watchEndpoint":{"videoId":"song0000001","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}}}]}}},
                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                          {"text":"Artist A","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartistA","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
                          {"text":" • "},{"text":"Album X","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]}}}]}}]}},
                {"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"New albums"}]}}},
                  "contents":[
                    {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Album X"}]},
                      "subtitle":{"runs":[{"text":"Single"},{"text":" • "},{"text":"Artist A","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartistA","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]},
                      "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
                      "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3/album=w226-h226","width":226}]}}},
                      "thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"OLAK5uy_x"}}}}}}}},
                    {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Artist B"}]},
                      "navigationEndpoint":{"browseEndpoint":{"browseId":"UCartistB","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}}]}}],
              "continuations":[{"nextContinuationData":{"continuation":"HOME2"}}]}}}}]}}}
            """,
        )
        val page = MusicParser.page(json)
        assertEquals(listOf("Quick picks", "New albums"), page.shelves.map { it.title })
        assertEquals("HOME2", page.continuation)

        val song = page.shelves[0].items.single()
        assertEquals("song0000001", song.id)
        assertEquals(MusicType.Song, song.type)
        assertEquals("Song One", song.title)
        assertEquals(listOf(MusicArtistRef("Artist A", "UCartistA")), song.artists)
        assertEquals("https://lh3/song=w544-h544-l90", song.thumbnail)

        val (album, artist) = page.shelves[1].items
        assertEquals(MusicType.Single, album.type)
        assertEquals("MPREb_x", album.id)
        assertEquals("OLAK5uy_x", album.playlistId)
        assertEquals(MusicType.Artist, artist.type)
    }

    @Test
    fun parsesAlbumHeaderAndTracks() {
        val json = JSONObject(
            """
            {"contents":{"twoColumnBrowseResultsRenderer":{
              "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{
                "title":{"runs":[{"text":"Album X"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2024"}]},
                "straplineTextOne":{"runs":[{"text":"Artist A"}]},
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3/a=w544-h544","width":544}]}}},
                "buttons":[{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"t1","playlistId":"OLAK5uy_x"}}}}]}}]}}}}],
              "secondaryContents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
                {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track000001"},
                  "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track 1"}]}}}],
                  "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:21"}]}}}]}},
                {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track000002"},
                  "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track 2"}]}}}]}}]}}]}}}}}
            """,
        )
        val page = MusicParser.page(json)
        assertEquals("Album X", page.title)
        assertEquals("Artist A • Album • 2024", page.subtitle)
        assertEquals("OLAK5uy_x", page.playlistId)
        val tracks = page.shelves.single().items
        assertEquals(listOf("Track 1", "Track 2"), tracks.map { it.title })
        assertEquals("3:21", tracks[0].duration)
        assertTrue(tracks.all { it.type == MusicType.Song })
    }

    @Test
    fun parsesRadioQueue() {
        val json = JSONObject(
            """
            {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{
              "contents":[
                {"playlistPanelVideoRenderer":{"videoId":"q1","title":{"runs":[{"text":"Q One"}]},
                  "longBylineText":{"runs":[{"text":"Artist A","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartistA"}}},{"text":" • Album • 2024"}]},
                  "lengthText":{"runs":[{"text":"2:59"}]},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/q1/hq.jpg","width":120}]}}},
                {"automixPreviewVideoRenderer":{}},
                {"playlistPanelVideoRenderer":{"videoId":"q2","title":{"runs":[{"text":"Q Two"}]}}}],
              "continuations":[{"nextRadioContinuationData":{"continuation":"RADIO2"}}]}}}}}}]}}}}}
            """,
        )
        val (items, next) = MusicParser.queue(json)
        assertEquals(listOf("q1", "q2"), items.map { it.id })
        assertEquals("Artist A", items[0].artists.single().name)
        assertEquals("2:59", items[0].duration)
        assertEquals("RADIO2", next)
        assertNull(MusicParser.queue(JSONObject("{}")).second)
    }
}
