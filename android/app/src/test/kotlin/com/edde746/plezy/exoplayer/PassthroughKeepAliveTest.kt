package com.edde746.plezy.exoplayer

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keepalive wrapper's contract with media3: while paused the track keeps playing and only
 * silence reaches it, the renderer's own writes are refused, the reported position never counts
 * the silence, and every exit path finishes a partial unit before handing the track back.
 */
@OptIn(UnstableApi::class)
class PassthroughKeepAliveTest {

  private val logs = mutableListOf<String>()
  private val log: (String, String, String) -> Unit = { level, prefix, message -> logs.add("$level [$prefix] $message") }

  private fun config(
    encoding: Int = AudioFormat.ENCODING_AC3,
    sampleRate: Int = 48_000,
    channelMask: Int = AudioFormat.CHANNEL_OUT_5POINT1,
    isTunneling: Boolean = false
  ): AudioOutputProvider.OutputConfig = AudioOutputProvider.OutputConfig.Builder()
    .setEncoding(encoding)
    .setSampleRate(sampleRate)
    .setChannelMask(channelMask)
    .setBufferSize(40_000)
    .setIsTunneling(isTunneling)
    .build()

  private val assets: (String) -> ByteArray? = { name ->
    when (name) {
      "ac3_51.bin" -> ByteArray(2560) { 0x51 }
      "ac3_20.bin" -> ByteArray(768) { 0x20 }
      "eac3_51.bin" -> ByteArray(3072) { 0x51 }
      "dts_51.bin" -> ByteArray(2048) { 0x51 }
      else -> null
    }
  }

  private fun ac3Silence() = KeepAliveSilence.forConfig(config(), isCarrier = false, assets = assets, log = log)!!

  private fun carrierSilence() = KeepAliveSilence.forConfig(
    config(encoding = AudioFormat.ENCODING_PCM_16BIT, sampleRate = 192_000, channelMask = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND),
    isCarrier = true,
    assets = assets,
    log = log
  )!!

  private fun wrap(delegate: FakeOutput, silence: KeepAliveSilence, sampleRate: Int = 48_000) =
    KeepAliveAudioOutput(delegate, silence, sampleRate, log, targetAheadUs = 200_000L, spawnFeederThread = false)

  /** A real AC3 access unit: media3 parses 1536 frames from a 48kHz syncframe header. */
  private fun ac3AccessUnit(): ByteBuffer = ByteBuffer.wrap(ByteArray(2560).also {
    it[0] = 0x0b; it[1] = 0x77; it[4] = 0x3e // fscod=48k, frmsizecod for 640kbps
    it[5] = 0x40 // bsid 8: a plain AC3 header, which media3 reads as 1536 frames
  })

  @Test
  fun silenceSelectionFollowsEncodingLayoutAndRate() {
    val ac3 = KeepAliveSilence.forConfig(config(), false, assets, log)
    assertNotNull(ac3)
    assertEquals(2560, ac3!!.unit.size)
    assertEquals(1536, ac3.framesPerUnit)
    assertFalse(ac3.isPcm)

    val stereo = KeepAliveSilence.forConfig(config(channelMask = AudioFormat.CHANNEL_OUT_STEREO), false, assets, log)
    assertEquals(768, stereo!!.unit.size)

    val joc = KeepAliveSilence.forConfig(config(encoding = C.ENCODING_E_AC3_JOC), false, assets, log)
    assertEquals("Atmos JOC falls back to plain E-AC3 silence", 3072, joc!!.unit.size)

    val dts = KeepAliveSilence.forConfig(config(encoding = AudioFormat.ENCODING_DTS), false, assets, log)
    assertEquals(512, dts!!.framesPerUnit)

    assertNull("tunneled tracks pause normally", KeepAliveSilence.forConfig(config(isTunneling = true), false, assets, log))
    assertNull("only 48kHz frames are bundled", KeepAliveSilence.forConfig(config(sampleRate = 44_100), false, assets, log))
    assertNull("PCM output is the mixer's business", KeepAliveSilence.forConfig(config(encoding = AudioFormat.ENCODING_PCM_16BIT), false, assets, log))
    assertNull("raw TrueHD has no silence source", KeepAliveSilence.forConfig(config(encoding = AudioFormat.ENCODING_DOLBY_TRUEHD), false, assets, log))
  }

  @Test
  fun carrierSilenceIsTenMillisecondsOfZeroFrames() {
    val silence = carrierSilence()
    assertTrue(silence.isPcm)
    assertEquals(16, silence.pcmFrameSize)
    assertEquals(1920, silence.framesPerUnit)
    assertEquals(1920 * 16, silence.unit.size)
    assertTrue(silence.unit.all { it == 0.toByte() })
  }

  @Test
  fun pauseKeepsTheTrackPlayingAndRefusesRealAudio() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))

    output.pause()

    assertFalse("the delegate must not pause", delegate.paused)
    assertTrue(output.isHolding)
    assertFalse("the renderer's audio is refused while holding", output.write(ac3AccessUnit(), 1, 0L))
    assertEquals("nothing but the first unit reached the track", 1, delegate.writes.size)
  }

  @Test
  fun silenceStartsOnlyOnceTheRealTailHasDrained() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    // 20 units = 640ms of real audio written, none played yet.
    repeat(20) { assertTrue(output.write(ac3AccessUnit(), 1, 0L)) }
    output.pause()

    assertFalse("640ms queued is above the 200ms target", output.feedOnce())
    assertEquals(0L, output.silenceFrames)

    // The head reaches 480ms played: 160ms of real audio left, below target.
    delegate.playedFrames = 1536L * 15
    assertTrue(output.feedOnce())
    assertEquals(1536L, output.silenceFrames)
    assertTrue(delegate.writes.last().contentEquals(ByteArray(2560) { 0x51 }))
    // 160ms + 32ms is still under target, so a second unit goes in; 224ms is not.
    assertTrue(output.feedOnce())
    assertFalse("the queue is back above target", output.feedOnce())
    assertEquals(1536L * 2, output.silenceFrames)
  }

  @Test
  fun reportedPositionNeverCountsSilenceAndNeverRunsBackwards() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    repeat(4) { assertTrue(output.write(ac3AccessUnit(), 1, 0L)) } // 128ms real
    delegate.playedFrames = 1536L * 2 // 64ms played
    assertEquals(64_000L, output.getPositionUs())

    output.pause()
    assertTrue(output.feedOnce()) // queued 64ms < 200ms target: 32ms of silence goes in
    assertEquals("silence written ahead must not pull the clock back", 64_000L, output.getPositionUs())

    delegate.playedFrames = 1536L * 4 // the real tail has fully played
    assertEquals("the clock stays frozen for the whole hold", 64_000L, output.getPositionUs())
    assertTrue(output.feedOnce())
    output.play()
    delegate.playedFrames = 1536L * 6 // the tail and the two silence units have played
    assertEquals("after release, played silence is invisible to the clock", 128_000L, output.getPositionUs())
  }

  /**
   * media3 1.11 treats a paused audio renderer as ready only while the sink has pending data:
   * position behind written frames. A hold that let the clock reach the written frames turned
   * the renderer not-ready, parked the player in BUFFERING, and the resume that ends the hold
   * could never come (the stalled DD+ resume on a Shield).
   */
  @Test
  fun aHoldKeepsTheClockBehindTheWrittenFramesEvenIfTheHeadOvershoots() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    repeat(4) { assertTrue(output.write(ac3AccessUnit(), 1, 0L)) } // 128ms written
    delegate.playedFrames = 1536L * 3 // 96ms played
    assertEquals(96_000L, output.getPositionUs())

    output.pause()
    assertTrue(output.feedOnce()) // 32ms queued is under target: one silence unit goes in
    // The head estimate races ahead of everything written, silence included.
    delegate.playedFrames = 1536L * 40
    assertEquals("frozen at the pause position", 96_000L, output.getPositionUs())

    output.play()
    assertEquals("and never beyond the real audio written", 128_000L, output.getPositionUs())
  }

  @Test
  fun playEndsTheHoldWithoutTouchingTheTrack() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    assertEquals(1, delegate.playCalls)
    output.pause()
    output.play()

    assertFalse(output.isHolding)
    assertEquals("the track never paused, so play is not repeated", 1, delegate.playCalls)
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
  }

  @Test
  fun aPartialUnitIsCompletedBeforeAnythingElse() {
    val delegate = FakeOutput(sampleRate = 48_000, acceptBytes = 1000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    output.pause()

    assertTrue(output.feedOnce()) // 1000 of 2560 bytes went in
    assertEquals(0L, output.silenceFrames)
    delegate.acceptBytes = Int.MAX_VALUE
    assertTrue(output.feedOnce())
    assertEquals(1536L, output.silenceFrames)
    assertEquals("the unit arrives whole across the two writes", 2560, delegate.writes.sumOf { it.size })
  }

  @Test
  fun aFailedSilenceWriteFallsBackToARealPause() {
    val delegate = FakeOutput(sampleRate = 48_000, failWrites = true)
    val output = wrap(delegate, ac3Silence())
    output.play()
    output.pause()

    assertFalse(output.feedOnce())
    assertTrue("the track pauses for real when silence cannot be written", delegate.paused)
    output.play()
    assertEquals("and play then resumes it", 2, delegate.playCalls)
  }

  @Test
  fun flushResetsTheAccounting() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    output.pause()
    assertTrue(output.feedOnce())
    output.flush()

    assertFalse(output.isHolding)
    assertEquals(0L, output.silenceFrames)
    assertTrue(delegate.flushed)
    delegate.playedFrames = 0
    assertEquals(0L, output.getPositionUs())
  }

  @Test
  fun providerOnlyWrapsWhenEnabledAndArmable() {
    val provider = FakeOutputProvider()
    var enabled = false
    val wrapped = KeepAliveOutputProvider(provider, { enabled }, isCarrier = false, assets = assets, log = log)

    assertTrue(wrapped.getAudioOutput(config()) is FakeOutput)
    enabled = true
    assertTrue(wrapped.getAudioOutput(config()) is KeepAliveAudioOutput)
    assertTrue("PCM output stays untouched even when enabled", wrapped.getAudioOutput(config(encoding = AudioFormat.ENCODING_PCM_16BIT)) is FakeOutput)
    assertTrue(logs.any { it.contains("Armed: ac3 silence") })
  }

  private class FakeOutputProvider : AudioOutputProvider {
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) = throw UnsupportedOperationException()
    override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig = throw UnsupportedOperationException()
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = FakeOutput(config.sampleRate)
    override fun addListener(listener: AudioOutputProvider.Listener) = Unit
    override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
    override fun release() = Unit
  }

  private class FakeOutput(
    private val sampleRate: Int,
    var acceptBytes: Int = Int.MAX_VALUE,
    var failWrites: Boolean = false
  ) : AudioOutput {
    var playedFrames = 0L
    var paused = false
    var playCalls = 0
    var flushed = false
    val writes = mutableListOf<ByteArray>()

    override fun play() {
      playCalls++
      paused = false
    }

    override fun pause() {
      paused = true
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
      if (failWrites) throw AudioOutput.WriteException(-1, false)
      val n = minOf(buffer.remaining(), acceptBytes)
      val bytes = ByteArray(n)
      buffer.get(bytes)
      writes.add(bytes)
      return !buffer.hasRemaining()
    }

    override fun flush() {
      flushed = true
      playedFrames = 0
    }

    override fun stop() = Unit
    override fun release() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = 0
    override fun getSampleRate() = sampleRate
    override fun getBufferSizeInFrames() = 48_000L
    override fun getPositionUs(): Long = playedFrames * 1_000_000L / sampleRate
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) = Unit
    override fun removeListener(listener: AudioOutput.Listener) = Unit
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun setPlayerId(playerId: PlayerId) = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
  }
}
