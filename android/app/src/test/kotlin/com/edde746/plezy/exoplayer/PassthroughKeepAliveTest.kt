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
import org.junit.Assert.assertSame
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

  /** The wrapper's wall clock; tests move it by hand. */
  private var nowMs = 1_000_000L

  private fun wrap(delegate: FakeOutput, silence: KeepAliveSilence, sampleRate: Int = 48_000) =
    KeepAliveAudioOutput(delegate, silence, sampleRate, log, targetAheadUs = 200_000L, spawnFeederThread = false, clock = { nowMs })

  /**
   * Lets the head grace after a restart pass and feeds silence until the queue is full, which
   * includes the one verifying read of the head. Returns the units written.
   */
  private fun settleBridge(output: KeepAliveAudioOutput): Int {
    nowMs += 150
    var units = 0
    while (output.feedOnce()) units++
    return units
  }

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
  fun aTrackThatDiesUnderTheFeederIsGivenUpRatherThanCrashingTheProcess() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    output.pause()
    assertTrue(output.feedOnce())
    // On a dead track media3's output has its provider reset the audio capabilities before it
    // throws; the provider asserts the playback thread, which the feeder is not.
    delegate.writeThrows = IllegalStateException("AudioTrackAudioOutputProvider accessed on multiple threads: ExoPlayer:Playback and null")

    assertFalse(output.feedOnce())
    assertTrue("the track is paused for real", delegate.paused)
    assertFalse("and is not handed back after a seek", output.reacquire())
    assertTrue(logs.toString(), logs.any { it.startsWith("warn") && "silence write failed (IllegalStateException" in it })
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

  // --- Seek bridging ---

  /** A playing track, paused (held) and then flushed: the state every seek leaves behind. */
  private fun seekDuringAHold(delegate: FakeOutput, output: KeepAliveAudioOutput) {
    output.play()
    repeat(4) { assertTrue(output.write(ac3AccessUnit(), 1, 0L)) }
    delegate.playedFrames = 1536L * 2
    output.pause()
    assertTrue(output.feedOnce())
    delegate.events.clear()
    output.flush()
  }

  @Test
  fun aFlushDuringAHoldStartsABridge() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)

    assertEquals("the track is flushed only while paused, then restarted at once", listOf("pause", "flush", "play"), delegate.events)
    assertTrue(output.isBridging)
    assertFalse(output.isHolding)
    assertEquals("the accounting restarts with media3's", 0L, output.silenceFrames)
    assertTrue("the flushed track is empty, so silence goes in without waiting", output.feedOnce())
    assertEquals(1536L, output.silenceFrames)
    assertTrue(delegate.writes.last().contentEquals(ByteArray(2560) { 0x51 }))
    assertTrue(logs.any { it.contains("[keepalive] Bridging: ac3 silence") && it.contains("flush that ended a hold") })
  }

  @Test
  fun aFlushOfAPlayingTrackAlsoBridges() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    delegate.events.clear()

    output.flush()

    assertEquals(listOf("pause", "flush", "play"), delegate.events)
    assertTrue(output.isBridging)
  }

  @Test
  fun aFlushOfAPausedTrackDoesNotBridge() {
    val delegate = FakeOutput(sampleRate = 48_000, failWrites = true)
    val output = wrap(delegate, ac3Silence())
    output.play()
    output.pause()
    assertFalse(output.feedOnce()) // the silence write fails and the track pauses for real
    delegate.events.clear()

    output.flush()

    assertEquals("nothing to keep alive: a plain flush", listOf("flush"), delegate.events)
    assertFalse(output.isBridging)
  }

  @Test
  fun theFirstRealWriteEndsTheBridge() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)
    val units = settleBridge(output)
    assertEquals("the 200ms target in 32ms units, once the real head (at zero) is read", 7, units)
    output.play() // the player reached READY before the renderer had anything to write

    assertTrue(output.write(ac3AccessUnit(), 1, 0L))

    assertFalse(output.isBridging)
    assertFalse("the sink is playing, so no hold follows", output.isHolding)
    assertEquals(1536L * units, output.silenceFrames)
    assertEquals(units + 1, delegate.writes.size)
    assertFalse("no more silence once real audio flows", output.feedOnce())
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    assertTrue(logs.any { it.contains("[keepalive] Bridge ended (first real write)") && it.contains("wrote $units units") })
  }

  @Test
  fun thePositionIsZeroWhileBridging() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)

    assertEquals(0L, output.getPositionUs())
    repeat(3) { assertTrue(output.feedOnce()) }
    assertEquals("silence written ahead must not pull the clock below zero", 0L, output.getPositionUs())
    delegate.playedFrames = 1536L * 2 // 64ms of bridge silence has played
    assertEquals("played silence is invisible to the clock", 0L, output.getPositionUs())
    delegate.playedFrames = 1536L * 50 // the head estimate overshoots everything written
    assertEquals("nothing real was written, so nothing real can have played", 0L, output.getPositionUs())

    delegate.playedFrames = 0
    val units = 3 + settleBridge(output)
    delegate.playedFrames = 1536L * 50
    assertEquals("still nothing real, whatever the head says", 0L, output.getPositionUs())
    output.play()
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    delegate.playedFrames = 1536L * units // the silence units have played, the real unit has not
    assertEquals(0L, output.getPositionUs())
    delegate.playedFrames = 1536L * (units + 1) // and now the real unit has
    assertEquals(32_000L, output.getPositionUs())
  }

  @Test
  fun aPartialSilenceUnitCompletesBeforeTheRealWriteGoesThrough() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)
    val units = settleBridge(output)
    delegate.playedFrames = 1536L * (units - 2) // the head has moved on and the queue is short again
    delegate.acceptBytes = 1000
    assertTrue(output.feedOnce()) // 1000 of 2560 bytes went in
    assertEquals(1536L * units, output.silenceFrames)
    delegate.acceptBytes = Int.MAX_VALUE
    output.play()

    assertTrue(output.write(ac3AccessUnit(), 1, 0L))

    assertEquals("the rest of the silence unit, then the real unit", listOf(1000, 1560, 2560), delegate.writes.takeLast(3).map { it.size })
    assertEquals(1536L * (units + 1), output.silenceFrames)
    assertFalse(output.isBridging)
  }

  /**
   * media3 writes the new position's first audio before it restarts the renderers, so the first
   * real write lands while the sink is still paused. The track must not play on into it: the
   * bridge becomes a hold, with that one unit keeping the renderer ready (written > position).
   */
  @Test
  fun aBridgeWhoseSinkIsStillPausedTurnsIntoAHoldAtTheFirstRealWrite() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)
    val units = settleBridge(output)

    assertTrue("the first unit goes in", output.write(ac3AccessUnit(), 1, 0L))

    assertFalse(output.isBridging)
    assertTrue(output.isHolding)
    assertFalse("the next one is refused until the sink plays", output.write(ac3AccessUnit(), 1, 0L))
    assertEquals(0L, output.getPositionUs())
    delegate.playedFrames = 1536L * (units + 1) // the bridge silence and the real unit have played
    assertEquals("frozen until play", 0L, output.getPositionUs())
    assertFalse("the track never paused", delegate.paused)
    assertEquals("the queue is short again, so the hold feeds silence", true, output.feedOnce())

    output.play()
    assertFalse(output.isHolding)
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    assertEquals("play never reached the still-playing track", 2, delegate.playCalls) // one from play(), one from the bridge
  }

  /**
   * On a direct track the head comes from the HAL's render counter, which resets only when the
   * audio thread gets to the flush; a read before that stores the pre-seek count and the reset
   * then looks to media3 like a 32-bit wrap. So nothing reads the head for a grace period after
   * a restart, real audio waits, and one read then checks that the counter did reset.
   */
  @Test
  fun theHeadIsLeftUnreadAfterARestartAndCheckedOnce() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)
    delegate.positionReads = 0

    assertEquals(0L, output.getPositionUs())
    repeat(3) { assertTrue("silence is paced by the clock meanwhile", output.feedOnce()) }
    assertFalse("real audio waits for the check", output.write(ac3AccessUnit(), 1, 0L))
    nowMs += 149
    assertEquals(0L, output.getPositionUs())
    assertEquals("nothing reads the head during the grace", 0, delegate.positionReads)
    assertTrue(output.isBridging)

    nowMs += 1
    while (output.feedOnce()) Unit
    assertTrue(logs.any { it.contains("[keepalive] Playback head verified 150ms after the restart") })
    assertTrue(delegate.positionReads > 0)
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    assertFalse(output.isBridging)
  }

  @Test
  fun aStaleHeadAfterTheFlushIsFlushedAgain() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)
    // media3 clamps a wrapped head to exactly the frames written.
    delegate.positionFn = { output.silenceFrames * 1_000_000L / 48_000 }
    delegate.events.clear()

    nowMs += 150
    while (output.feedOnce()) Unit

    assertEquals("flushed and restarted once more", listOf("pause", "flush", "play"), delegate.events)
    assertTrue(logs.any { it.contains("[keepalive] Playback head stale 150ms after the flush") && it.contains("extra restart 1 of 3") })
    assertTrue(output.isBridging)
    assertFalse("and real audio keeps waiting", output.write(ac3AccessUnit(), 1, 0L))

    delegate.positionFn = null // this time the counter did reset
    nowMs += 150
    while (output.feedOnce()) Unit
    assertTrue(logs.any { it.contains("[keepalive] Playback head verified") && it.contains("(after 1 extra restart)") })
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    assertFalse(output.isBridging)
  }

  @Test
  fun aHeadThatNeverResetsIsGivenUpOnAfterThreeExtraRestarts() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    seekDuringAHold(delegate, output)
    delegate.positionFn = { output.silenceFrames * 1_000_000L / 48_000 }
    delegate.events.clear()

    repeat(3) {
      nowMs += 150
      while (output.feedOnce()) Unit
    }
    assertEquals(3, delegate.events.count { it == "flush" })
    nowMs += 150

    assertTrue("real audio flows again, out of sync rather than never", output.write(ac3AccessUnit(), 1, 0L))
    assertTrue(logs.any { it.contains("giving up after 3 extra restarts") })
    assertEquals("no fourth flush", 3, delegate.events.count { it == "flush" })
    assertFalse(output.isBridging)
  }

  /**
   * A stale counter that outlasts the grace poisons media3's tracker later: from then on the
   * delegate's position sits exactly on the frames written. A second of that while real audio
   * keeps arriving restarts the track; the discarded queue counts as played and the clock goes on
   * from where it was.
   */
  @Test
  fun aClockPinnedToTheWrittenFramesMidPlaybackRestartsTheTrack() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    var realUnits = 0
    repeat(4) { assertTrue(output.write(ac3AccessUnit(), 1, 0L)); realUnits++ }
    delegate.playedFrames = 1536L * 2
    assertEquals(64_000L, output.getPositionUs())

    delegate.positionFn = { realUnits * 1536L * 1_000_000L / 48_000 } // the tracker took a wrap
    assertEquals(128_000L, output.getPositionUs())
    nowMs += 999
    repeat(16) { assertTrue(output.write(ac3AccessUnit(), 1, 0L)); realUnits++ } // 512ms of real audio arrives
    assertEquals(640_000L, output.getPositionUs())
    assertTrue("a second has not passed", delegate.events.none { it == "flush" })

    nowMs += 1
    assertEquals(640_000L, output.getPositionUs())

    assertEquals(listOf("pause", "flush", "play"), delegate.events.takeLast(3))
    assertTrue(output.isBridging)
    assertTrue(logs.any { it.contains("[keepalive] The clock has sat on the frames written for 1000ms") })
    assertTrue(logs.any { it.contains("[keepalive] Bridging: ac3 silence") && it.contains("across the stale clock") })
    delegate.positionFn = null // the restarted track's head is sane
    assertEquals("the clock goes on from where it was", 640_000L, output.getPositionUs())

    val units = settleBridge(output)
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    realUnits++
    assertFalse(output.isBridging)
    assertFalse("the sink is playing, so no hold", output.isHolding)
    assertEquals(640_000L, output.getPositionUs())
    delegate.playedFrames = 1536L * units // the bridge silence has played
    assertEquals(640_000L, output.getPositionUs())
    delegate.playedFrames = 1536L * (units + 1) // and the first real unit after the restart
    assertEquals(672_000L, output.getPositionUs())
  }

  @Test
  fun anEndOfStreamStopDuringAHoldWaitsForTheResume() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence())
    output.play()
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    output.pause()

    output.stop()

    assertFalse("stopping would end the bitstream", delegate.events.contains("stop"))
    assertTrue(output.isHolding)
    output.play()
    assertFalse(output.isHolding)
    assertEquals("the deferred stop goes through on the resume", "stop", delegate.events.last())
  }

  @Test
  fun aReleasedBridgingOutputIsParkedAndHandedBackForTheSameConfig() {
    val provider = FakeOutputProvider()
    val wrapped = KeepAliveOutputProvider(provider, { true }, isCarrier = false, assets = assets, log = log, spawnFeederThread = false)
    val output = wrapped.getAudioOutput(config()) as KeepAliveAudioOutput
    val delegate = provider.outputs.single()
    val listener = RecordingListener()
    output.addListener(listener)
    output.play()
    assertTrue(output.write(ac3AccessUnit(), 1, 0L))
    output.pause()
    delegate.events.clear()

    output.release()

    assertEquals("the track is restarted, not released", listOf("pause", "flush", "play"), delegate.events)
    assertTrue(output.isBridging)
    assertEquals("media3's release is answered at once", 1, listener.releases)
    assertEquals("and its listener no longer hangs off the track", 0, delegate.listeners.size)

    assertSame("the next request for the same config gets the parked track back", output, wrapped.getAudioOutput(config()))
    assertTrue(output.isBridging)
    assertEquals(1, provider.outputs.size)
    assertTrue(logs.any { it.contains("[keepalive] Reacquired after") })
  }

  @Test
  fun aParkedOutputIsReleasedForRealWhenTheNextTrackDiffers() {
    val provider = FakeOutputProvider()
    val wrapped = KeepAliveOutputProvider(provider, { true }, isCarrier = false, assets = assets, log = log, spawnFeederThread = false)
    val output = wrapped.getAudioOutput(config()) as KeepAliveAudioOutput
    val delegate = provider.outputs.single()
    output.play()
    output.release()
    assertTrue(output.isBridging)

    val next = wrapped.getAudioOutput(config(channelMask = AudioFormat.CHANNEL_OUT_STEREO))

    assertTrue(delegate.released)
    assertFalse(output.isBridging)
    assertTrue(next is KeepAliveAudioOutput && next !== output)
    assertEquals(2, provider.outputs.size)
  }

  @Test
  fun releasingTheProviderReleasesAParkedOutput() {
    val provider = FakeOutputProvider()
    val wrapped = KeepAliveOutputProvider(provider, { true }, isCarrier = false, assets = assets, log = log, spawnFeederThread = false)
    val output = wrapped.getAudioOutput(config()) as KeepAliveAudioOutput
    output.play()
    output.release()

    wrapped.release()

    assertTrue(provider.outputs.single().released)
    assertFalse(output.isBridging)
    assertTrue(provider.released)
  }

  /** The carrier sink and the normal sink have their own providers but share one HDMI route. */
  @Test
  fun aTrackParkedByOneProviderIsReleasedWhenTheOtherBuildsItsOwn() {
    val lot = KeepAliveParkingLot()
    val rawProvider = FakeOutputProvider()
    val carrierProvider = FakeOutputProvider()
    val raw = KeepAliveOutputProvider(rawProvider, { true }, isCarrier = false, assets = assets, log = log, spawnFeederThread = false, lot = lot)
    val carrier = KeepAliveOutputProvider(carrierProvider, { true }, isCarrier = true, assets = assets, log = log, spawnFeederThread = false, lot = lot)
    val output = raw.getAudioOutput(config()) as KeepAliveAudioOutput
    output.play()
    output.release()
    assertTrue(output.isBridging)

    val carrierOutput = carrier.getAudioOutput(
      config(encoding = AudioFormat.ENCODING_PCM_16BIT, sampleRate = 192_000, channelMask = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
    )

    assertTrue("the parked AC3 track is gone before the carrier track exists", rawProvider.outputs.single().released)
    assertFalse(output.isBridging)
    assertTrue(carrierOutput is KeepAliveAudioOutput)
    carrier.release()
    assertTrue(carrierProvider.released)
    assertFalse("a live track is media3's to release, not the provider's", carrierProvider.outputs.single().released)
  }

  @Test
  fun aReleaseWithNowhereToParkGoesThrough() {
    val delegate = FakeOutput(sampleRate = 48_000)
    val output = wrap(delegate, ac3Silence()) // no park callback
    output.play()
    output.pause()
    assertTrue(output.feedOnce())

    output.release()

    assertTrue(delegate.released)
    assertFalse(output.isBridging)
    assertFalse(output.isHolding)
  }

  @Test
  fun aBrokenTrackIsNotHandedBack() {
    val provider = FakeOutputProvider()
    val wrapped = KeepAliveOutputProvider(provider, { true }, isCarrier = false, assets = assets, log = log, spawnFeederThread = false)
    val output = wrapped.getAudioOutput(config()) as KeepAliveAudioOutput
    val delegate = provider.outputs.single()
    output.play()
    output.release()
    delegate.failWrites = true
    assertFalse(output.feedOnce()) // the bridge silence fails; the track pauses for real
    assertTrue(delegate.paused)

    val next = wrapped.getAudioOutput(config())

    assertTrue(delegate.released)
    assertTrue(next !== output)
  }

  private class RecordingListener : AudioOutput.Listener {
    var releases = 0
    override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) = Unit
    override fun onOffloadDataRequest() = Unit
    override fun onOffloadPresentationEnded() = Unit
    override fun onUnderrun() = Unit
    override fun onReleased() {
      releases++
    }
  }

  private class FakeOutputProvider : AudioOutputProvider {
    val outputs = mutableListOf<FakeOutput>()
    var released = false
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) = throw UnsupportedOperationException()
    override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig = throw UnsupportedOperationException()
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = FakeOutput(config.sampleRate).also { outputs.add(it) }
    override fun addListener(listener: AudioOutputProvider.Listener) = Unit
    override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
    override fun release() {
      released = true
    }
  }

  private class FakeOutput(
    private val sampleRate: Int,
    var acceptBytes: Int = Int.MAX_VALUE,
    var failWrites: Boolean = false
  ) : AudioOutput {
    /** When set, every write throws it, the way media3's output surfaces a dead track off the playback thread. */
    var writeThrows: RuntimeException? = null
    var playedFrames = 0L
    /** When set, answers getPositionUs instead of [playedFrames]: a head media3 clamps to the frames written, say. */
    var positionFn: (() -> Long)? = null
    var positionReads = 0
    var paused = false
    var playCalls = 0
    var flushed = false
    var released = false
    val writes = mutableListOf<ByteArray>()
    val events = mutableListOf<String>()
    val listeners = mutableListOf<AudioOutput.Listener>()

    override fun play() {
      playCalls++
      paused = false
      events.add("play")
    }

    override fun pause() {
      paused = true
      events.add("pause")
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
      if (failWrites) throw AudioOutput.WriteException(-1, false)
      writeThrows?.let { throw it }
      val n = minOf(buffer.remaining(), acceptBytes)
      val bytes = ByteArray(n)
      buffer.get(bytes)
      writes.add(bytes)
      return !buffer.hasRemaining()
    }

    override fun flush() {
      flushed = true
      playedFrames = 0
      writes.clear() // whatever was queued is discarded
      events.add("flush")
    }

    override fun stop() {
      events.add("stop")
    }

    override fun release() {
      released = true
      events.add("release")
    }
    override fun setVolume(volume: Float) = Unit
    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = 0
    override fun getSampleRate() = sampleRate
    override fun getBufferSizeInFrames() = 48_000L
    override fun getPositionUs(): Long {
      positionReads++
      return positionFn?.invoke() ?: (playedFrames * 1_000_000L / sampleRate)
    }
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) {
      listeners.add(listener)
    }
    override fun removeListener(listener: AudioOutput.Listener) {
      listeners.remove(listener)
    }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun setPlayerId(playerId: PlayerId) = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
  }
}
