package com.edde746.plezy.exoplayer

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DtsUtil
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps a bitstream (passthrough) AudioTrack playing across a pause by feeding it silence.
 *
 * Pausing an AudioTrack that carries Dolby/DTS stops the HDMI bitstream, and receivers such as a
 * Trinnov then drop the stream and spend 15-30s re-locking on resume. Instead of pausing the track
 * this wrapper refuses the renderer's real audio while paused and writes codec-shaped silence from
 * a feeder thread, so the receiver keeps seeing a continuous stream of the same format.
 *
 * What "silence" is depends on the track:
 * - The IEC 61937 carrier (TrueHD, DTS-HD MA) is PCM-shaped, so zero frames are legal stuffing.
 * - Raw AC3/E-AC3/DTS tracks need real encoded frames; one pre-encoded silent frame per codec
 *   and layout ships in the assets (`passthrough_silence/`). Android wraps these into IEC 61937
 *   bursts itself (AudioFlinger's SpdifStreamOut), so a frame must fit its burst with the
 *   8-byte preamble: a 512-sample DTS frame gets a 2048-byte burst, which is why the DTS
 *   silence is the 1006-byte "768 kbps" shape and not the 2048-byte 1536 kbps one that a Shield
 *   silently discarded.
 * - Everything else (PCM, tunneled tracks, raw TrueHD/DTS-HD, AC4) is left alone: PCM is kept
 *   alive by the platform mixer, and the others have no silence source yet.
 *
 * Position accounting: silence played counts in the AudioTrack's head position but not in
 * media3's written frames, so [getPositionUs] subtracts every silence frame written. Real audio
 * already queued in the track at the moment of pause plays out before the silence starts; that
 * tail (up to one passthrough buffer) is heard after the pause press, and the clock resumes
 * after it. This is a known prototype artifact.
 *
 * Known limitation: object formats (Atmos JOC, TrueHD Atmos, DTS:X) have no silence encoder.
 * Their carrier gets zero stuffing and their raw E-AC3 track gets plain E-AC3 silence, which
 * the receiver may treat as a format change.
 */
@OptIn(UnstableApi::class)
internal class KeepAliveSilence private constructor(
  val label: String,
  /** One unit of silence, written whole. */
  val unit: ByteArray,
  /** Track frames one unit stands for, at the track's sample rate. */
  val framesPerUnit: Int,
  /** Non-zero for a PCM-shaped track: bytes per frame, so real writes can be counted. */
  val pcmFrameSize: Int,
  val encoding: Int
) {
  val isPcm: Boolean get() = pcmFrameSize > 0

  /** Frames one encoded access unit of the real stream holds, parsed like media3 does. */
  fun framesPerAccessUnit(buffer: ByteBuffer): Int {
    if (isPcm) return 0
    return try {
      when (encoding) {
        C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> Ac3Util.parseAc3SyncframeAudioSampleCount(buffer)
        C.ENCODING_DTS -> DtsUtil.parseDtsAudioSampleCount(buffer)
        else -> 0
      }.takeIf { it > 0 } ?: framesPerUnit
    } catch (_: RuntimeException) {
      framesPerUnit
    }
  }

  companion object {
    /** Where the silent frames live in the APK's assets. */
    const val ASSET_DIR = "passthrough_silence"

    /** Sample rate every bundled frame was encoded at. */
    private const val ASSET_SAMPLE_RATE = 48_000

    /**
     * The silence for an output configuration, or null with the reason logged when the track
     * should pause normally. [isCarrier] marks the IEC 61937 carrier's PCM-shaped track.
     */
    fun forConfig(
      config: AudioOutputProvider.OutputConfig,
      isCarrier: Boolean,
      assets: (String) -> ByteArray?,
      log: ((String, String, String) -> Unit)?
    ): KeepAliveSilence? {
      fun skip(reason: String): KeepAliveSilence? {
        log?.invoke("info", "keepalive", "Not armed for encoding=${config.encoding} rate=${config.sampleRate}: $reason")
        return null
      }
      if (config.isTunneling) return skip("tunneled track")
      val channelCount = Integer.bitCount(config.channelMask)
      if (isCarrier) {
        if (config.encoding != C.ENCODING_PCM_16BIT) return skip("carrier is not 16-bit PCM shaped")
        val frameSize = channelCount * 2
        val frames = config.sampleRate / 100 // 10ms
        return KeepAliveSilence("IEC 61937 zero stuffing", ByteArray(frames * frameSize), frames, frameSize, config.encoding)
      }
      val codec = when (config.encoding) {
        C.ENCODING_AC3 -> "ac3"
        C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> "eac3"
        C.ENCODING_DTS -> "dts"
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_8BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> return skip("PCM output, the mixer keeps it alive")
        else -> return skip("no silence source for this encoding")
      }
      if (config.sampleRate != ASSET_SAMPLE_RATE) return skip("only 48kHz silence is bundled")
      val layout = if (channelCount >= 6) "51" else "20"
      val name = "${codec}_$layout.bin"
      val frame = assets(name) ?: return skip("asset $name missing")
      val frames = if (codec == "dts") 512 else 1536
      return KeepAliveSilence("$codec silence ($layout, ${frame.size}B/frame)", frame, frames, 0, config.encoding)
    }
  }
}

/** Loads the bundled silent frames once per process. */
internal class SilenceAssets(context: Context) {
  private val appContext = context.applicationContext
  private val cache = HashMap<String, ByteArray?>()

  @Synchronized
  fun load(name: String): ByteArray? = cache.getOrPut(name) {
    try {
      appContext.assets.open("${KeepAliveSilence.ASSET_DIR}/$name").use { it.readBytes() }
    } catch (_: java.io.IOException) {
      null
    }
  }
}

/**
 * Wraps every output a provider hands out in [KeepAliveAudioOutput] when the feature is on and
 * the track has a silence source. [enabled] is read per track, so a toggle lands on the next
 * AudioTrack rather than mid-stream.
 */
@OptIn(UnstableApi::class)
internal class KeepAliveOutputProvider(
  private val delegate: AudioOutputProvider,
  private val enabled: () -> Boolean,
  private val isCarrier: Boolean,
  private val assets: (String) -> ByteArray?,
  private val log: ((String, String, String) -> Unit)?
) : AudioOutputProvider by delegate {

  override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
    val real = delegate.getAudioOutput(config)
    if (!enabled()) return real
    val silence = KeepAliveSilence.forConfig(config, isCarrier, assets, log) ?: return real
    log?.invoke(
      "info",
      "keepalive",
      "Armed: ${silence.label} on encoding=${config.encoding} rate=${config.sampleRate} " +
        "channels=${Integer.bitCount(config.channelMask)} buffer=${config.bufferSize}"
    )
    return KeepAliveAudioOutput(real, silence, config.sampleRate, log)
  }
}

@OptIn(UnstableApi::class)
internal class KeepAliveAudioOutput(
  private val delegate: AudioOutput,
  private val silence: KeepAliveSilence,
  private val sampleRate: Int,
  private val log: ((String, String, String) -> Unit)?,
  /** How much audio (real tail plus silence) to keep queued ahead of the playback head. */
  targetAheadUs: Long = 200_000L,
  /** Tests drive [feedOnce] themselves instead of spawning the feeder thread. */
  private val spawnFeederThread: Boolean = true
) : AudioOutput {

  private val lock = Any()
  private val targetAheadFrames = targetAheadUs * sampleRate / 1_000_000L

  /** True between a keepalive pause and the join of its feeder; the renderer's writes are refused. */
  @Volatile private var holding = false

  /** Set by play/flush/stop/release to end the feeder; the feeder finishes a partial unit first. */
  @Volatile private var stopRequested = false

  /** The feeder could not write and the delegate was paused for real instead. */
  @Volatile private var delegatePaused = false

  private var feeder: Thread? = null

  @Volatile private var realFramesWritten = 0L
  private val silenceFramesWritten = AtomicLong(0L)
  private var framesPerAccessUnit = 0
  private var lastReportedUs = Long.MIN_VALUE

  /** The position reported when the hold began; the clock stays there until release. */
  @Volatile private var positionAtPauseUs = Long.MIN_VALUE

  /** Feeder-only: a unit the delegate accepted partially; it must complete before anything else. */
  private var pendingUnit: ByteBuffer? = null
  private var unitsWritten = 0L
  private var holdStartedMs = 0L

  val isHolding: Boolean get() = holding
  val silenceFrames: Long get() = silenceFramesWritten.get()

  override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
    if (holding) return false
    if (!silence.isPcm && framesPerAccessUnit == 0 && buffer.hasRemaining()) {
      framesPerAccessUnit = silence.framesPerAccessUnit(buffer)
    }
    val before = buffer.remaining()
    val handled = delegate.write(buffer, encodedAccessUnitCount, presentationTimeUs)
    if (silence.isPcm) {
      realFramesWritten += (before - buffer.remaining()) / silence.pcmFrameSize
    } else if (handled) {
      realFramesWritten += framesPerAccessUnit.toLong() * encodedAccessUnitCount
    }
    return handled
  }

  override fun getPositionUs(): Long {
    val raw = delegate.getPositionUs()
    val silenceFrames = silenceFramesWritten.get()
    if (silenceFrames == 0L && !holding) {
      // Remember it: the first silence unit is written ahead of play, and the guard below must
      // know where the clock was before that write.
      lastReportedUs = raw
      return raw
    }
    var adjusted = raw - framesToUs(silenceFrames)
    // While paused media3 counts the audio renderer as ready only while the sink still has
    // pending data, i.e. the position is behind the frames written. A hold must therefore
    // report a frozen clock, as a real pause does: if the head's estimate ever overshot and the
    // reported position reached the written frames, the renderer would turn not-ready, the
    // player would sit in BUFFERING, and its resume could never arrive to end the hold.
    if (holding && positionAtPauseUs != Long.MIN_VALUE && adjusted > positionAtPauseUs) adjusted = positionAtPauseUs
    // And at no time can more real audio have played than was written.
    val writtenUs = framesToUs(realFramesWritten)
    if (adjusted > writtenUs) adjusted = writtenUs
    // Silence is written ahead of being played, so the subtraction can dip below the last
    // report; the clock must not run backwards.
    val reported = if (adjusted < lastReportedUs) lastReportedUs else adjusted
    lastReportedUs = reported
    return reported
  }

  override fun pause() {
    synchronized(lock) {
      if (holding) return
      if (delegatePaused) {
        delegate.pause()
        return
      }
      positionAtPauseUs = getPositionUs()
      holding = true
      stopRequested = false
      holdStartedMs = System.currentTimeMillis()
      unitsWritten = 0
      val queued = queuedFrames()
      log?.invoke(
        "info",
        "keepalive",
        "Engaged: ${silence.label}; ~${framesToUs(queued) / 1000}ms of real audio queued, " +
          "silence starts once that drains to ${targetAheadFrames * 1000 / sampleRate}ms"
      )
      if (spawnFeederThread) {
        feeder = Thread({ runFeeder() }, "plezy-keepalive").apply {
          priority = Thread.MAX_PRIORITY
          start()
        }
      }
    }
  }

  override fun play() {
    val wasHolding = stopFeeder("resume")
    if (wasHolding && !delegatePaused) return // the track never paused
    delegatePaused = false
    delegate.play()
  }

  override fun flush() {
    stopFeeder("flush")
    realFramesWritten = 0
    silenceFramesWritten.set(0)
    framesPerAccessUnit = 0
    lastReportedUs = Long.MIN_VALUE
    positionAtPauseUs = Long.MIN_VALUE
    pendingUnit = null
    delegatePaused = false
    delegate.flush()
  }

  override fun stop() {
    stopFeeder("stop")
    delegate.stop()
  }

  override fun release() {
    stopFeeder("release")
    delegate.release()
  }

  /** Ends a hold and waits for the feeder to finish its unit; returns whether a hold was active. */
  private fun stopFeeder(reason: String): Boolean {
    val thread: Thread?
    synchronized(lock) {
      if (!holding) return false
      stopRequested = true
      thread = feeder
      feeder = null
    }
    thread?.join(1_000L)
    synchronized(lock) {
      holding = false
      positionAtPauseUs = Long.MIN_VALUE
    }
    log?.invoke(
      "info",
      "keepalive",
      "Released ($reason) after ${System.currentTimeMillis() - holdStartedMs}ms: wrote $unitsWritten units this hold, " +
        "${framesToUs(silenceFramesWritten.get()) / 1000}ms of silence since the track opened" +
        if (delegatePaused) "; the track had been paused for real" else ""
    )
    return true
  }

  private fun runFeeder() {
    var lastHeadUs = delegate.getPositionUs()
    var headCheckedAtMs = System.currentTimeMillis()
    var stallLogged = false
    try {
      while (true) {
        if (stopRequested && pendingUnit == null) return
        val wrote = feedOnce()
        if (!wrote) Thread.sleep(FEED_POLL_MS)
        // A head that stops moving while silence is accepted means the platform is dropping the
        // frames (a DTS frame too large for its IEC burst did exactly this): the receiver loses the
        // stream and, worse, the subtracted silence never played, so the clock falls behind.
        val now = System.currentTimeMillis()
        if (now - headCheckedAtMs >= HEAD_STALL_CHECK_MS) {
          val headUs = delegate.getPositionUs()
          if (headUs == lastHeadUs && unitsWritten > 0 && !stallLogged) {
            stallLogged = true
            log?.invoke(
              "warn",
              "keepalive",
              "Playback head stuck at ${headUs / 1000}ms for ${now - headCheckedAtMs}ms while $unitsWritten silence units " +
                "were accepted (${silence.label}); the platform is probably discarding them"
            )
          }
          lastHeadUs = headUs
          headCheckedAtMs = now
        }
      }
    } catch (_: InterruptedException) {
      // stopFeeder's join timed out; leave quietly.
    }
  }

  /**
   * One feeder step: completes a partial unit, or writes a fresh one when the queue is short.
   * Returns whether anything was written. On a write failure the delegate is paused for real
   * and the hold ends.
   */
  fun feedOnce(): Boolean {
    if (delegatePaused) return false
    val unit = pendingUnit ?: run {
      if (stopRequested) return false
      if (queuedFrames() >= targetAheadFrames) return false
      ByteBuffer.wrap(silence.unit)
    }
    val before = unit.remaining()
    val handled = try {
      delegate.write(unit, 1, 0L)
    } catch (e: AudioOutput.WriteException) {
      log?.invoke("warn", "keepalive", "Silence write failed (${e.errorCode}); pausing the track for real")
      pendingUnit = null
      delegatePaused = true
      delegate.pause()
      return false
    }
    if (handled) {
      pendingUnit = null
      silenceFramesWritten.addAndGet(silence.framesPerUnit.toLong())
      unitsWritten++
      return true
    }
    pendingUnit = unit
    return unit.remaining() != before
  }

  /** Frames written (real and silence) that the playback head has not reached. */
  private fun queuedFrames(): Long {
    val headFrames = usToFrames(delegate.getPositionUs())
    return realFramesWritten + silenceFramesWritten.get() - headFrames
  }

  private fun framesToUs(frames: Long): Long = frames * 1_000_000L / sampleRate
  private fun usToFrames(us: Long): Long = if (us <= 0L) 0L else us * sampleRate / 1_000_000L

  override fun setVolume(volume: Float) = delegate.setVolume(volume)
  override fun isOffloadedPlayback() = delegate.isOffloadedPlayback()
  override fun getAudioSessionId() = delegate.getAudioSessionId()
  override fun getSampleRate() = delegate.getSampleRate()
  override fun getBufferSizeInFrames() = delegate.getBufferSizeInFrames()
  override fun getPlaybackParameters() = delegate.getPlaybackParameters()
  override fun isStalled() = delegate.isStalled()
  override fun addListener(listener: AudioOutput.Listener) = delegate.addListener(listener)
  override fun removeListener(listener: AudioOutput.Listener) = delegate.removeListener(listener)
  override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = delegate.setPlaybackParameters(playbackParameters)
  override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)
  override fun setOffloadEndOfStream() = delegate.setOffloadEndOfStream()
  override fun setPlayerId(playerId: PlayerId) = delegate.setPlayerId(playerId)
  override fun attachAuxEffect(effectId: Int) = delegate.attachAuxEffect(effectId)
  override fun setAuxEffectSendLevel(level: Float) = delegate.setAuxEffectSendLevel(level)
  override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = delegate.setPreferredDevice(preferredDevice)

  private companion object {
    const val FEED_POLL_MS = 5L
    const val HEAD_STALL_CHECK_MS = 1_500L
  }
}
