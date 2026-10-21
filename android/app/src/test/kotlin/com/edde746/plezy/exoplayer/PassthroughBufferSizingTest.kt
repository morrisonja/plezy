package com.edde746.plezy.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PassthroughBufferSizingTest {

  private val logs = mutableListOf<String>()

  /** Sizes the way media3 does: half a second at the bitrate, or at the codec's ceiling when unknown. */
  private val media3Like = DefaultAudioSink.AudioTrackBufferSizeProvider { min, encoding, mode, _, _, bitrate, _ ->
    if (mode != DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH) return@AudioTrackBufferSizeProvider 65_536
    val ceilingBps = when (encoding) {
      C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> 6_144_000
      C.ENCODING_AC3 -> 640_000
      else -> 1_536_000
    }
    val bps = if (bitrate != Format.NO_VALUE) bitrate else ceilingBps
    maxOf(min, bps / 8 / 2)
  }

  private fun provider(spec: String) =
    HintedPassthroughBufferSizeProvider(media3Like, { PassthroughBufferSizing.parse(spec) }, { l, p, m -> logs.add("$l [$p] $m") })

  private fun size(p: HintedPassthroughBufferSizeProvider, encoding: Int, mode: Int = DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH, bitrate: Int = Format.NO_VALUE) =
    p.getBufferSizeInBytes(4_096, encoding, mode, 1, 48_000, bitrate, 1.0)

  @Test
  fun parsesTheWireFormatAndSkipsWhatDoesNotFit() {
    assertEquals(
      listOf(AudioBitrateHint("eac3", 6, 768), AudioBitrateHint("ac3", 6, 448)),
      PassthroughBufferSizing.parse("EAC3:6:768,ac3:6:448,truehd:8,dts:6:abc,:6:100,aac:2:0")
    )
    assertTrue(PassthroughBufferSizing.parse("").isEmpty())
    assertTrue(PassthroughBufferSizing.parse(null).isEmpty())
  }

  @Test
  fun aDdPlusStreamGetsABufferForItsOwnBitrateWithHeadroom() {
    // 768 kbps with the 1.5x margin is 1152 kbps: 72000B for half a second, not the 384000B
    // that the 6144 kbps ceiling gives.
    assertEquals(72_000, size(provider("eac3:6:768"), C.ENCODING_E_AC3))
    assertEquals(72_000, size(provider("eac3:6:768"), C.ENCODING_E_AC3_JOC))
    assertEquals(2, logs.size)
    assertTrue(logs.first(), logs.first().startsWith("info [audio] Passthrough buffer sized from the stream's bitrate: 72000B"))
  }

  @Test
  fun theLargestOfSeveralDdPlusStreamsWins() {
    assertEquals(96_000, size(provider("eac3:6:768,eac3:8:1024"), C.ENCODING_E_AC3))
  }

  @Test
  fun aSuspiciouslyLowBitrateIsFlooredAndNothingGrowsPastMedia3sChoice() {
    assertEquals(18_000, size(provider("eac3:6:64"), C.ENCODING_E_AC3)) // 192 kbps * 1.5 / 8 / 2
    assertEquals(384_000, size(provider("eac3:6:9000"), C.ENCODING_E_AC3)) // the ceiling, unchanged
  }

  @Test
  fun otherCodecsPcmAndKnownBitratesAreLeftToMedia3() {
    val p = provider("eac3:6:768,ac3:6:448,dts:6:1509")
    assertEquals(40_000, size(p, C.ENCODING_AC3))
    assertEquals(96_000, size(p, C.ENCODING_DTS))
    assertEquals(65_536, size(p, C.ENCODING_E_AC3, mode = DefaultAudioSink.OUTPUT_MODE_PCM))
    assertEquals(32_000, size(p, C.ENCODING_E_AC3, bitrate = 512_000))
    assertEquals(384_000, size(provider(""), C.ENCODING_E_AC3))
    assertTrue(logs.isEmpty())
  }
}
