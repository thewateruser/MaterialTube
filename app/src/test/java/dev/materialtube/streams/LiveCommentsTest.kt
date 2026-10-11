package dev.materialtube.streams

import dev.materialtube.data.net.FallbackDns
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/** Real comments through NewPipeExtractor. Runs only with MATERIALTUBE_LIVE_TEST=1. */
class LiveCommentsTest {

    @Before
    fun setUp() {
        assumeTrue(System.getenv("MATERIALTUBE_LIVE_TEST") == "1")
        NewPipe.init(NewPipeDownloader(FallbackDns.client()))
    }

    @Test
    fun loadsCommentsAndReplies() {
        val page = Comments.first("dQw4w9WgXcQ")
        println("comments: count=${page.count} first=${page.items.firstOrNull()?.let { "${it.author}: ${it.text.take(80)}" }} next=${page.next != null}")
        assertTrue(page.items.isNotEmpty())
        page.next?.let { assertTrue(Comments.more("dQw4w9WgXcQ", it).items.isNotEmpty()) }
        page.items.firstOrNull { it.repliesPage != null }?.let { c ->
            val replies = Comments.more("dQw4w9WgXcQ", c.repliesPage!!)
            println("replies for ${c.author}: ${replies.items.size}")
            assertTrue(replies.items.isNotEmpty())
        }
    }
}
