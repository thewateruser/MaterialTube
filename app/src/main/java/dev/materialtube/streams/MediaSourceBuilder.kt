@file:OptIn(UnstableApi::class)

package dev.materialtube.streams

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.materialtube.Graph
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

class BuiltSource(
    val source: MediaSource,
    /** Height actually selected, null for adaptive (HLS/DASH live) or audio-only sources. */
    val height: Int?,
    /** Heights the user can switch between. */
    val availableHeights: List<Int>,
)

/**
 * Builds ExoPlayer sources from NewPipeExtractor results, mirroring what NewPipe does: separate
 * video-only and audio streams are wrapped in generated DASH manifests and merged, which gives
 * up to 4K while letting ExoPlayer seek efficiently.
 */
object MediaSourceBuilder {

    private val requestNumber = AtomicLong()

    /** googlevideo wants POST + `rn` like the web player; everything else is a plain GET. */
    private val dataSourceFactory: DataSource.Factory by lazy {
        val http = OkHttpDataSource.Factory(Graph.http)
            .setUserAgent(NewPipeDownloader.USER_AGENT)
        ResolvingDataSource.Factory(http) { spec -> resolve(spec) }
    }

    private fun resolve(spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.host?.endsWith("googlevideo.com") != true || uri.path?.contains("videoplayback") != true) return spec
        val newUri = if (uri.getQueryParameter("rn") == null) {
            uri.buildUpon().appendQueryParameter("rn", requestNumber.incrementAndGet().toString()).build()
        } else {
            uri
        }
        val headers = HashMap(spec.httpRequestHeaders)
        if (uri.getQueryParameter("c")?.startsWith("WEB") == true) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
        }
        return spec.buildUpon()
            .setUri(newUri)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(0x78, 0))
            .setHttpRequestHeaders(headers)
            .build()
    }

    fun build(info: StreamInfo, maxHeight: Int, metadata: MediaMetadata): BuiltSource {
        val item = MediaItem.Builder()
            .setMediaId(info.id)
            .setMediaMetadata(metadata)

        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            info.hlsUrl?.takeIf { it.isNotEmpty() }?.let {
                return BuiltSource(
                    HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item.setUri(it).build()),
                    null, emptyList(),
                )
            }
            info.dashMpdUrl?.takeIf { it.isNotEmpty() }?.let {
                return BuiltSource(
                    DashMediaSource.Factory(dataSourceFactory).createMediaSource(item.setUri(it).build()),
                    null, emptyList(),
                )
            }
        }

        val videoOnly = info.videoOnlyStreams.filter(::usable)
        val audio = info.audioStreams.filter(::usable)
        val heights = videoOnly.map { it.height }.filter { it > 0 }.distinct().sortedDescending()

        if (audio.isNotEmpty()) {
            val bestAudio = pickAudio(audio)
            val audioSource = dash(bestAudio, info, item.setUri(bestAudio.content).build())
            if (heights.isEmpty()) return BuiltSource(audioSource, null, emptyList())
            val target = heights.firstOrNull { it <= maxHeight } ?: heights.last()
            val video = videoOnly.filter { it.height == target }.minBy { codecRank(it.codec) }
            val videoSource = dash(video, info, item.setUri(video.content).build())
            return BuiltSource(MergingMediaSource(true, true, videoSource, audioSource), target, heights)
        }

        // Fallback: a muxed progressive stream (usually 360p).
        val muxed = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedByDescending { it.height }
        val pick = muxed.firstOrNull { it.height <= maxHeight } ?: muxed.lastOrNull()
        if (pick != null) {
            return BuiltSource(
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item.setUri(pick.content).build()),
                pick.height, emptyList(),
            )
        }
        info.hlsUrl?.takeIf { it.isNotEmpty() }?.let {
            return BuiltSource(HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item.setUri(it).build()), null, emptyList())
        }
        throw IllegalStateException("No playable streams found")
    }

    private fun usable(s: Stream): Boolean =
        s.isUrl && s.itagItem != null &&
            (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || s.deliveryMethod == DeliveryMethod.DASH)

    /** H.264 decodes in hardware everywhere; VP9 is next; AV1 only when nothing else exists. */
    private fun codecRank(codec: String?): Int = when {
        codec == null -> 3
        codec.startsWith("avc") -> 0
        codec.startsWith("vp9") || codec.startsWith("vp09") -> 1
        codec.startsWith("av01") -> 2
        else -> 3
    }

    private fun pickAudio(audio: List<AudioStream>): AudioStream {
        val original = audio.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { audio }
        return original.maxWith(
            compareBy<AudioStream> { it.codec?.startsWith("mp4a") == true }.thenBy { it.averageBitrate },
        )
    }

    private fun dash(stream: Stream, info: StreamInfo, mediaItem: MediaItem): MediaSource {
        val manifestXml = if (stream.deliveryMethod == DeliveryMethod.DASH) {
            YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, stream.itagItem!!, info.duration)
        } else {
            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, stream.itagItem!!, info.duration)
        }
        val manifest = DashManifestParser().parse(Uri.parse(stream.content), ByteArrayInputStream(manifestXml.toByteArray()))
        return DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, mediaItem)
    }
}
