package dev.ytplus.data.stats

import dev.ytplus.data.PlaybackTracking
import dev.ytplus.data.auth.TvOAuth
import dev.ytplus.data.innertube.InnerTube
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom

/**
 * Reports playback to YouTube the same way the official apps do, so videos land in your watch
 * history and the recommendation algorithm learns from what you watch here.
 *
 * YouTube hands out per-account tracking URLs in the player response; we ping
 * `videostatsPlaybackUrl` once when playback starts and `videostatsWatchtimeUrl` with the
 * watched segment periodically and on pause/stop.
 */
class WatchStats(private val http: OkHttpClient, private val auth: TvOAuth?) {

    fun session(tracking: PlaybackTracking, lengthSeconds: Long?) = Session(tracking, lengthSeconds)

    inner class Session internal constructor(
        private val tracking: PlaybackTracking,
        private val lengthSeconds: Long?,
    ) {
        val cpn: String = newCpn()
        private var started = false
        private var segmentStart: Double? = null
        private var lastPosition = 0.0
        private val startedAt = System.currentTimeMillis()

        /** Call when playback actually starts rendering. */
        fun onStart(positionSeconds: Double) {
            if (started) return
            started = true
            segmentStart = positionSeconds
            lastPosition = positionSeconds
            tracking.playbackUrl?.let { ping(it, mapOf("cmt" to fmt(positionSeconds))) }
        }

        /** Call every ~10–30s while playing, plus on pause/seek/stop with [state] "paused". */
        fun onProgress(positionSeconds: Double, state: String = "playing") {
            if (!started) return
            val st = segmentStart ?: positionSeconds
            val url = tracking.watchtimeUrl ?: return
            if (positionSeconds > st) {
                ping(
                    url,
                    mapOf(
                        "st" to fmt(st),
                        "et" to fmt(positionSeconds),
                        "cmt" to fmt(positionSeconds),
                        "state" to state,
                        "final" to if (state == "ended") "1" else null,
                    ),
                )
            }
            segmentStart = if (state == "playing") positionSeconds else null
            lastPosition = positionSeconds
        }

        /** Seeking starts a new segment so watch-time isn't double counted. */
        fun onSeek(newPositionSeconds: Double) {
            if (started) segmentStart = newPositionSeconds
        }

        private fun ping(baseUrl: String, extra: Map<String, String?>) {
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()?.apply {
                setQueryParameter("ver", "2")
                setQueryParameter("cpn", cpn)
                setQueryParameter("c", InnerTube.CLIENT_NAME)
                setQueryParameter("cver", InnerTube.CLIENT_VERSION)
                setQueryParameter("rt", fmt((System.currentTimeMillis() - startedAt) / 1000.0))
                lengthSeconds?.let { setQueryParameter("len", it.toString()) }
                extra.forEach { (k, v) -> if (v != null) setQueryParameter(k, v) }
            }?.build() ?: return
            val token = runCatching { auth?.accessToken() }.getOrNull()
            val request = Request.Builder().url(url)
                .header("User-Agent", TvOAuth.TV_USER_AGENT)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            runCatching { http.newCall(request).execute().close() }
        }
    }

    companion object {
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val random = SecureRandom()

        /** Client playback nonce: 16 chars from YouTube's url-safe base64 alphabet. */
        fun newCpn(): String = buildString { repeat(16) { append(CPN_ALPHABET[random.nextInt(64)]) } }

        internal fun fmt(seconds: Double) = String.format(java.util.Locale.US, "%.3f", seconds)
    }
}
