package dev.materialtube.streams

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description

data class Comment(
    val id: String,
    val author: String,
    val avatar: String?,
    /** HTML for YouTube comments (links, timestamps, emphasis). */
    val text: String,
    val isHtml: Boolean,
    val likes: String?,
    val published: String?,
    val replyCount: Int,
    val repliesPage: Page?,
    val pinned: Boolean,
    val hearted: Boolean,
    val byUploader: Boolean,
)

data class CommentsPage(
    val items: List<Comment>,
    val next: Page?,
    val count: Int? = null,
    val disabled: Boolean = false,
)

/** Comments via NewPipeExtractor, which tracks YouTube's comment format changes for us. */
object Comments {

    private fun url(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    fun first(videoId: String): CommentsPage {
        val info = CommentsInfo.getInfo(ServiceList.YouTube, url(videoId))
        return CommentsPage(
            items = info.relatedItems.map(::map),
            next = info.nextPage?.takeIf { info.hasNextPage() },
            count = info.commentsCount.takeIf { it > 0 },
            disabled = info.isCommentsDisabled,
        )
    }

    /** Next page of top-level comments, or a page of replies when [page] is a comment's replies page. */
    fun more(videoId: String, page: Page): CommentsPage {
        val result = CommentsInfo.getMoreItems(ServiceList.YouTube, url(videoId), page)
        return CommentsPage(result.items.map(::map), result.nextPage?.takeIf { result.hasNextPage() })
    }

    /** First page for a sort-order continuation token (see InnerTube.commentSorts). */
    fun fromToken(videoId: String, token: String): CommentsPage = more(videoId, Page(url(videoId), token))

    private fun map(c: CommentsInfoItem): Comment {
        val description: Description? = c.commentText
        return Comment(
            id = c.commentId ?: "${c.uploaderName}-${c.textualUploadDate}",
            author = c.uploaderName.orEmpty(),
            avatar = c.uploaderAvatars.maxByOrNull { it.height }?.url,
            text = description?.content().orEmpty(),
            isHtml = description?.type() == Description.Type.HTML,
            likes = c.textualLikeCount?.takeIf { it.isNotBlank() && it != "0" }
                ?: c.likeCount.takeIf { it > 0 }?.toString(),
            published = c.textualUploadDate,
            replyCount = c.replyCount.coerceAtLeast(0),
            repliesPage = c.replies,
            pinned = c.isPinned,
            hearted = c.isHeartedByUploader,
            byUploader = c.isChannelOwner,
        )
    }
}
