package com.edde746.plezy.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.min

/** One audio stream as the server describes it: codec name, channel count and bitrate in kbps. */
internal data class AudioBitrateHint(val codec: String, val channels: Int, val bitrateKbps: Int)

/**
 * Sizes a passthrough AudioTrack buffer from the stream's real bitrate when the server reports one.
 *
 * media3 sizes a passthrough buffer in bytes for the codec's highest possible bitrate, because the
 * container rarely says what the stream actually runs at. For E-AC-3 that ceiling is 6144 kbps,
 * while most DD+ streams run between 256 and 1700 kbps, so a buffer meant to hold half a second
 * holds four or more. Everything in it plays out after a pause before the keepalive silence
 * starts, and AudioFlinger will not start a direct track until the buffer has been filled once, so
 * the queue cannot be capped from the client side either (a cap below the buffer size deadlocks
 * the track at start). The buffer itself has to be right, and it is chosen when the track is made.
 *
 * Only E-AC-3 is resized. AC-3 and DTS buffers are already within a factor of two of their streams,
 * and the AC-3 one was widened deliberately (see [PlezyRenderersFactory]). The hinted rate carries a
 * margin for a reported bitrate that is a little low, is floored at the lowest rate DD+ is found at,
 * and the result is never larger than what media3 would pick on its own.
 */
internal object PassthroughBufferSizing {
  /** Headroom over the reported bitrate. */
  const val MARGIN = 1.5

  /** No DD+ stream runs below this, whatever the metadata says. */
  const val FLOOR_KBPS = 192

  /** Parses `codec:channels:kbps,codec:channels:kbps`; entries that do not fit are skipped. */
  fun parse(spec: String?): List<AudioBitrateHint> {
    if (spec.isNullOrBlank()) return emptyList()
    return spec.split(',').mapNotNull { entry ->
      val parts = entry.trim().split(':')
      if (parts.size != 3) return@mapNotNull null
      val codec = parts[0].trim().lowercase()
      val channels = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
      val kbps = parts[2].trim().toIntOrNull() ?: return@mapNotNull null
      if (codec.isEmpty() || kbps <= 0) return@mapNotNull null
      AudioBitrateHint(codec, channels, kbps)
    }
  }

  /** The bitrate in bps to size a buffer for, or [Format.NO_VALUE] when the hints do not apply. */
  fun hintedBitrateBps(encoding: Int, hints: List<AudioBitrateHint>): Int {
    if (encoding != C.ENCODING_E_AC3 && encoding != C.ENCODING_E_AC3_JOC) return Format.NO_VALUE
    // Several E-AC-3 streams (a 5.1 and an Atmos 7.1, say) cannot be told apart here; the
    // largest is the safe choice for all of them.
    val kbps = hints.filter { isEac3(it.codec) }.maxOfOrNull { it.bitrateKbps } ?: return Format.NO_VALUE
    return (maxOf(kbps, FLOOR_KBPS) * MARGIN).toInt() * 1000
  }

  fun isEac3(codec: String): Boolean = when (codec.trim().lowercase()) {
    "eac3", "e-ac-3", "ec-3", "ec3", "eac-3", "ddp", "dd+" -> true
    else -> false
  }
}

/**
 * media3's buffer-size provider with the stream's bitrate filled in from the server's hints when
 * the format itself does not carry one; see [PassthroughBufferSizing].
 */
@OptIn(UnstableApi::class)
internal class HintedPassthroughBufferSizeProvider(
  private val delegate: DefaultAudioSink.AudioTrackBufferSizeProvider,
  private val hints: () -> List<AudioBitrateHint>,
  private val log: ((String, String, String) -> Unit)?
) : DefaultAudioSink.AudioTrackBufferSizeProvider {

  override fun getBufferSizeInBytes(
    minBufferSizeInBytes: Int,
    encoding: Int,
    outputMode: Int,
    pcmFrameSize: Int,
    sampleRate: Int,
    bitrate: Int,
    maxAudioTrackPlaybackSpeed: Double
  ): Int {
    val default = delegate.getBufferSizeInBytes(
      minBufferSizeInBytes,
      encoding,
      outputMode,
      pcmFrameSize,
      sampleRate,
      bitrate,
      maxAudioTrackPlaybackSpeed
    )
    if (outputMode != DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH || bitrate != Format.NO_VALUE) return default
    val hinted = PassthroughBufferSizing.hintedBitrateBps(encoding, hints())
    if (hinted == Format.NO_VALUE) return default
    val sized = min(
      default,
      delegate.getBufferSizeInBytes(
        minBufferSizeInBytes,
        encoding,
        outputMode,
        pcmFrameSize,
        sampleRate,
        hinted,
        maxAudioTrackPlaybackSpeed
      )
    )
    log?.invoke(
      "info",
      "audio",
      "Passthrough buffer sized from the stream's bitrate: ${sized}B for encoding=$encoding at ${hinted / 1000}kbps " +
        "(margin included) where the codec's ceiling would have given ${default}B"
    )
    return sized
  }
}
