package dev.materialtube.data.sponsorblock

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

data class Segment(val startSeconds: Double, val endSeconds: Double, val category: String, val uuid: String)

/** Client for https://sponsor.ajay.app – community-submitted segments to skip. */
class SponsorBlock(private val http: OkHttpClient) {

    fun segments(videoId: String, categories: Collection<String>): List<Segment> {
        if (categories.isEmpty()) return emptyList()
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("categories", JSONArray(categories.toList()).toString())
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) return emptyList() // no segments for this video
            if (!response.isSuccessful) return emptyList()
            return parse(response.body?.string().orEmpty())
        }
    }

    companion object {
        val CATEGORIES = linkedMapOf(
            "sponsor" to "Sponsor",
            "selfpromo" to "Self promotion",
            "interaction" to "Interaction reminder",
            "intro" to "Intro",
            "outro" to "Endcards / credits",
            "preview" to "Preview / recap",
            "music_offtopic" to "Non-music section",
            "filler" to "Filler tangent",
        )
        val DEFAULT_CATEGORIES = setOf("sponsor", "selfpromo", "interaction")

        fun parse(json: String): List<Segment> {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val seg = o.optJSONArray("segment") ?: return@mapNotNull null
                // Only skip-type segments; "full" (whole video is sponsored) or "poi" aren't skippable.
                if (o.optString("actionType", "skip") != "skip") return@mapNotNull null
                Segment(seg.optDouble(0), seg.optDouble(1), o.optString("category"), o.optString("UUID"))
            }.filter { it.endSeconds > it.startSeconds }.sortedBy { it.startSeconds }
        }
    }
}
