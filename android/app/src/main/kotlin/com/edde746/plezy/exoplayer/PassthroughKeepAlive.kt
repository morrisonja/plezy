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
 * Seeks: media3 pauses the sink, releases the output, and asks for a new one only once the new
 * position has buffered, seconds later. A wrapper that is playing when it is flushed or released
 * bridges that gap instead: it pauses, flushes and restarts the delegate within milliseconds
 * (discarding the old position's tail and any hold silence), feeds silence at once, and on a
 * release parks itself in [KeepAliveOutputProvider] to be handed back for the next track of the
 * same config. The first real write ends the bridge. Because media3 writes that first audio
 * before it restarts the renderers, a bridge whose sink is still paused turns straight into a
 * hold after that write, so the new position is neither heard early nor allowed to catch the
 * clock up with the frames written (which would wedge the player in BUFFERING). The accounting
 * restarts at the bridge's flush, as media3's does: before the first real write the position
 * is zero, and bridge silence is subtracted like hold silence afterwards.
 *
 * The playback head after a flush: on a direct track Android serves AudioTrack's head position
 * from the HAL's render counter, which resets only when the audio thread gets to the flush, some
 * tens of milliseconds after flush() returns (AOSP itself notes the position "may not be zero in
 * STATE_FLUSHED due to hardware latency"). media3 resets its tracker at once, so a read in that
 * window stores the pre-seek count and the reset that follows looks like a 32-bit wrap: 2^32
 * frames are added for the life of the track, media3 clamps the position to the frames written,
 * rejects the (correct) hardware timestamps that disagree, and video runs ahead of audio by the
 * queue depth. So after a restart nobody reads the head for a grace period (the feeder paces its
 * silence by the clock, real writes wait, the position stays put), then one read checks that the
 * counter did reset; if not, the track is flushed and restarted again. Should a stale counter
 * still slip through, a clock that sits exactly on the frames written for a whole second while
 * real audio keeps arriving gives it away, and the track is restarted and bridged once more.
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
 * The one track a player keeps alive across a seek. Both sinks' providers (the normal one and
 * the IEC 61937 carrier's) share a lot, so a track parked by one can never hold the HDMI route
 * hostage while the other builds its own: any request that does not get the parked track back
 * releases it for real first.
 */
internal class KeepAliveParkingLot {
  private var parked: KeepAliveAudioOutput? = null
  private var parkedConfig: AudioOutputProvider.OutputConfig? = null
  private var owner: Any? = null

  fun park(owner: Any, output: KeepAliveAudioOutput, config: AudioOutputProvider.OutputConfig) {
    val previous = parked
    if (previous != null && previous !== output) previous.releaseForReal("displaced by a newer track")
    parked = output
    parkedConfig = config
    this.owner = owner
  }

  /**
   * Hands the parked track back when [owner] asks for the config it was parked with (and
   * [reusable] allows it); otherwise releases it for real and returns null. Either way the lot is
   * empty afterwards.
   */
  fun take(owner: Any, config: AudioOutputProvider.OutputConfig, reusable: Boolean): KeepAliveAudioOutput? {
    val held = parked ?: return null
    val match = reusable && this.owner === owner && parkedConfig == config && held.reacquire()
    clear()
    if (!match) {
      // The replacement is built while this track is still going away, as media3 itself does on
      // every flush; the alternatives are worse (see RawPositionOutputProvider).
      held.releaseForReal("not reusable for the next track")
      return null
    }
    return held
  }

  fun evict(owner: Any, reason: String) {
    if (this.owner !== owner) return
    parked?.releaseForReal(reason)
    clear()
  }

  private fun clear() {
    parked = null
    parkedConfig = null
    owner = null
  }
}

/**
 * Wraps every output a provider hands out in [KeepAliveAudioOutput] when the feature is on and
 * the track has a silence source. [enabled] is read per track, so a toggle lands on the next
 * AudioTrack rather than mid-stream.
 *
 * The provider is also where a seek is bridged. media3 releases the output on every flush, and
 * only asks for a new one once the new position has buffered, seconds later; a released
 * AudioTrack would take the bitstream down for all of that. A wrapper that is playing when it is
 * released parks itself in the [lot] instead and keeps feeding silence. The next request for an
 * identical config gets the parked wrapper back, still playing, and its first real write ends the
 * bridge. Any other request, a toggle of the feature or [release] evicts the parked track for real.
 */
@OptIn(UnstableApi::class)
internal class KeepAliveOutputProvider(
  private val delegate: AudioOutputProvider,
  private val enabled: () -> Boolean,
  private val isCarrier: Boolean,
  private val assets: (String) -> ByteArray?,
  private val log: ((String, String, String) -> Unit)?,
  /** Tests drive the wrappers' feeding themselves. */
  private val spawnFeederThread: Boolean = true,
  /** Shared between the providers of one player; see [KeepAliveParkingLot]. */
  private val lot: KeepAliveParkingLot = KeepAliveParkingLot()
) : AudioOutputProvider by delegate {

  private var released = false

  override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
    lot.take(this, config, reusable = enabled())?.let { return it }
    val real = delegate.getAudioOutput(config)
    if (!enabled()) return real
    val silence = KeepAliveSilence.forConfig(config, isCarrier, assets, log) ?: return real
    log?.invoke(
      "info",
      "keepalive",
      "Armed: ${silence.label} on encoding=${config.encoding} rate=${config.sampleRate} " +
        "channels=${Integer.bitCount(config.channelMask)} buffer=${config.bufferSize}"
    )
    return KeepAliveAudioOutput(
      real,
      silence,
      config.sampleRate,
      log,
      spawnFeederThread = spawnFeederThread,
      park = { output ->
        if (released) {
          false
        } else {
          lot.park(this, output, config)
          true
        }
      }
    )
  }

  override fun release() {
    released = true
    lot.evict(this, "provider released")
    delegate.release()
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
  private val spawnFeederThread: Boolean = true,
  /**
   * Takes this output when media3 releases it mid-seek so it can keep bridging; returns false
   * when nobody can hold it, in which case the release goes through for real.
   */
  private val park: ((KeepAliveAudioOutput) -> Boolean)? = null,
  /** A bridge nobody comes back for is cut after this long and the track paused for real. */
  private val bridgeTimeoutMs: Long = BRIDGE_TIMEOUT_MS,
  /** How long after a flush the playback head is left unread; see the class doc. */
  private val headGraceMs: Long = HEAD_GRACE_MS,
  /** Wall clock in ms; tests substitute their own. */
  private val clock: () -> Long = System::currentTimeMillis
) : AudioOutput {

  private val lock = Any()
  private val targetAheadFrames = targetAheadUs * sampleRate / 1_000_000L

  /** True between a keepalive pause and the join of its feeder; the renderer's writes are refused. */
  @Volatile private var holding = false

  /** True from a flush or release of a playing track until the first real write after the seek. */
  @Volatile private var bridging = false

  /** Set to end the feeder; the feeder finishes a partial unit first. */
  @Volatile private var stopRequested = false

  /** The delegate's AudioTrack is playing: play() reached it and nothing paused it for real since. */
  @Volatile private var delegatePlaying = false

  /** The delegate was told to stop (end of stream); it is draining and cannot be kept alive. */
  private var delegateStopped = false

  /** media3's own view of the track: play() was called more recently than pause(). */
  private var sinkPlaying = false

  /** A stop() swallowed while silence was being fed; forwarded once the feeding ends on a resume. */
  private var stopDeferred = false

  /** A bridge is waiting for its first real frames, which decide whether a hold must follow. */
  private var awaitingFirstRealWrite = false

  /** A silence write failed; the track is suspect and is not handed back after a seek. */
  @Volatile private var broken = false
  private var releasedForReal = false

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
  private var feedStartedMs = 0L

  /** When the latest flush-and-restart of the delegate completed. */
  private var restartedAtMs = 0L

  /** The head is not read before this time; see the class doc on the head after a flush. */
  @Volatile private var headUnreadableUntilMs = 0L

  /** The first head read after the restart confirmed that the HAL's counter was reset. */
  @Volatile private var headVerified = true

  /** Extra flushes spent on a counter that had not reset; bounded by [MAX_EXTRA_RESTARTS]. */
  private var restartAttempts = 0

  /** The restarts were given up on: the clock may run ahead, and the pinned-clock watch stands down. */
  private var headUnreliable = false

  /**
   * A restart the wrapper decided on itself (a stale clock mid-playback) discards real audio
   * media3 has already written. Those frames count as played: the reports continue from
   * [clockBaseUs], and only the real frames after [realFramesAtRestart] are in the delegate.
   */
  private var clockBaseUs = 0L
  private var realFramesAtRestart = 0L

  /** Pinned-clock watch: since when the delegate's position has sat on the frames written. */
  private var pinnedSinceMs = Long.MIN_VALUE
  private var realFramesAtPin = 0L

  /** Listeners registered through this wrapper, so a park can answer them and detach them. */
  private val listeners = ArrayList<AudioOutput.Listener>()

  val isHolding: Boolean get() = holding
  val isBridging: Boolean get() = bridging
  val silenceFrames: Long get() = silenceFramesWritten.get()

  override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
    if (holding) return false
    if (bridging) {
      // Nothing real goes in until the head has been read once and found reset: a stale counter
      // means another flush, which would discard it. Only the feeder restarts the track (it is
      // the one writing to it); without a feeder (tests) this write may.
      if (!maybeVerifyHead(allowRestart = feeder == null)) return false
      stopFeeder("first real write")
    }
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
    if (awaitingFirstRealWrite && realFramesWritten > 0) {
      awaitingFirstRealWrite = false
      // media3 fills the track before it restarts the renderers after a seek, so the first real
      // frames normally land while the sink is still paused. The track must not play on into
      // them: the new position would be heard early and, once the clock caught up with the frames
      // written, the renderer would turn not-ready and the player wedge in BUFFERING. Hold, as a
      // pause does, until play() arrives; the one unit just written is what keeps the renderer
      // ready (frames written > position) in the meantime.
      synchronized(lock) {
        if (!sinkPlaying && delegatePlaying && !holding) engageHold("the sink is still paused after the seek")
      }
    }
    return handled
  }

  override fun getPositionUs(): Long {
    if (!headVerified) {
      // The head is unreadable right after a restart (class doc), and nothing real has played
      // since: the clock sits where the restart left it.
      val frozen = if (lastReportedUs > clockBaseUs) lastReportedUs else clockBaseUs
      lastReportedUs = frozen
      return frozen
    }
    val raw = delegate.getPositionUs()
    val silenceFrames = silenceFramesWritten.get()
    val reported: Long
    if (silenceFrames == 0L && !holding && clockBaseUs == 0L) {
      // Remember it: the first silence unit is written ahead of play, and the guard below must
      // know where the clock was before that write.
      reported = raw
    } else {
      // The delegate's clock restarted at zero with the last flush; silence written since is
      // subtracted, and a self-restart's reports continue from where the clock was.
      var sinceRestartUs = raw - framesToUs(silenceFrames)
      // A bridge writes silence into a freshly flushed track before any real audio: nothing real
      // has played, so the clock is at zero, not below it.
      if (sinceRestartUs < 0L) sinceRestartUs = 0L
      var adjusted = clockBaseUs + sinceRestartUs
      // While paused media3 counts the audio renderer as ready only while the sink still has
      // pending data, i.e. the position is behind the frames written. A hold must therefore
      // report a frozen clock, as a real pause does: if the head's estimate ever overshot and the
      // reported position reached the written frames, the renderer would turn not-ready, the
      // player would sit in BUFFERING, and its resume could never arrive to end the hold.
      if (holding && positionAtPauseUs != Long.MIN_VALUE && adjusted > positionAtPauseUs) adjusted = positionAtPauseUs
      // And at no time can more real audio have played than was written. Before the first real
      // write after a seek this pins the clock at zero whatever the head reports.
      val writtenUs = framesToUs(realFramesWritten)
      if (adjusted > writtenUs) adjusted = writtenUs
      // Silence is written ahead of being played, so the subtraction can dip below the last
      // report; the clock must not run backwards.
      reported = if (adjusted < lastReportedUs) lastReportedUs else adjusted
    }
    lastReportedUs = reported
    watchForPinnedClock(raw)
    return reported
  }

  /**
   * A stale counter can also outlast the grace (a slow flush): the verification passes, and the
   * wrap poisons media3's tracker later. From then on the delegate's position is clamped to the
   * frames written, exactly, for the life of the track. A healthy track never does that for a
   * whole second while real audio keeps arriving (an underrun clamps for a moment and the written
   * frames then run ahead again), so that pattern restarts the track: its queue is discarded and
   * bridged with silence, and the clock continues from where it was.
   */
  private fun watchForPinnedClock(rawUs: Long) {
    if (!delegatePlaying || holding || bridging || delegateStopped || !sinkPlaying || headUnreliable) {
      pinnedSinceMs = Long.MIN_VALUE
      return
    }
    val writtenToDelegateUs = framesToUs(realFramesWritten - realFramesAtRestart + silenceFramesWritten.get())
    if (rawUs < writtenToDelegateUs - 1_000L) {
      pinnedSinceMs = Long.MIN_VALUE
      return
    }
    val now = clock()
    if (pinnedSinceMs == Long.MIN_VALUE) {
      pinnedSinceMs = now
      realFramesAtPin = realFramesWritten
      return
    }
    val pinnedForMs = now - pinnedSinceMs
    if (pinnedForMs < PINNED_CLOCK_MS) return
    // Half the window's worth of real audio must have arrived meanwhile, or this is a stall.
    if (realFramesWritten - realFramesAtPin < usToFrames(PINNED_CLOCK_MS * 500L)) return
    pinnedSinceMs = Long.MIN_VALUE
    log?.invoke(
      "warn",
      "keepalive",
      "The clock has sat on the frames written for ${pinnedForMs}ms while real audio kept arriving: the playback head " +
        "is stale (a 32-bit wrap after the last flush); restarting the track"
    )
    bridge("stale clock", keepClock = true)
  }

  override fun pause() {
    synchronized(lock) {
      sinkPlaying = false
      // Silence is already flowing; a bridge turns into a hold by itself at its first real write.
      if (holding || bridging) return
      if (!delegatePlaying) {
        delegate.pause()
        return
      }
      engageHold(null)
    }
  }

  override fun play() {
    synchronized(lock) { sinkPlaying = true }
    val endedHold = holding && stopFeeder("resume")
    synchronized(lock) {
      if (endedHold && stopDeferred) {
        // The stream ended while paused: let the track drain and stop as media3 asked.
        forwardStop()
        return
      }
      // A bridge keeps feeding: its first real write ends it, and the track is already playing.
      if (delegatePlaying) return
      if (delegateStopped) {
        delegate.play()
        return
      }
      delegatePlaying = true
      delegate.play()
    }
  }

  override fun flush() {
    if (canBridge()) {
      bridge("flush")
      return
    }
    stopFeeder("flush")
    synchronized(lock) {
      resetAccounting()
      stopDeferred = false
      delegateStopped = false
      delegatePlaying = false
      delegate.flush()
    }
  }

  override fun stop() {
    synchronized(lock) {
      if (holding || bridging) {
        // End of stream while paused or mid-seek. Stopping the track would end the bitstream, so
        // the stop waits for the resume; nothing more will be written in the meantime anyway.
        stopDeferred = true
        return
      }
      forwardStop()
    }
  }

  override fun release() {
    if (canBridge() && park != null && park.invoke(this)) {
      bridge("release")
      // media3 is done with this output; answer its release now (its pending-release count must
      // not stay open, see RawPositionOutputProvider) and let go of its listeners. The track
      // itself lives on, parked, until the next request for the same config.
      val notified = listeners.toList()
      listeners.clear()
      for (listener in notified) delegate.removeListener(listener)
      for (listener in notified) listener.onReleased()
      log?.invoke("info", "keepalive", "Parked while bridging; waiting for the next track of the same config")
      return
    }
    releaseForReal("release")
  }

  /** Releases the delegate for real, ending any hold or bridge first. */
  fun releaseForReal(reason: String) {
    if (releasedForReal) return
    releasedForReal = true
    stopFeeder(reason)
    synchronized(lock) {
      delegatePlaying = false
      delegate.release()
    }
  }

  /** Whether a parked output can be handed back for the next track; logs the reacquire. */
  fun reacquire(): Boolean {
    if (broken || releasedForReal) return false
    synchronized(lock) {
      log?.invoke(
        "info",
        "keepalive",
        "Reacquired after ${clock() - feedStartedMs}ms of bridging: wrote $unitsWritten units so far" +
          if (!delegatePlaying) "; the track had been paused for real" else ""
      )
    }
    return true
  }

  private fun canBridge(): Boolean = delegatePlaying && !delegateStopped && !broken

  /**
   * Bridges a seek: discards what the track still holds (the old position's tail and any hold
   * silence), restarts it within milliseconds so the output never goes idle, and feeds silence
   * until the first real write of the new position. media3 resets its written frames and its
   * position tracker on this flush, and the accounting here restarts with them. With [keepClock]
   * (a restart the wrapper decided on itself, mid-playback) the discarded real audio counts as
   * played and the clock continues from where it was.
   */
  private fun bridge(reason: String, keepClock: Boolean = false) {
    val wasHolding = holding
    stopFeeder(reason)
    synchronized(lock) {
      if (keepClock) {
        clockBaseUs = if (lastReportedUs > 0L) lastReportedUs else 0L
        realFramesAtRestart = realFramesWritten
        lastReportedUs = clockBaseUs
        positionAtPauseUs = Long.MIN_VALUE
        awaitingFirstRealWrite = false
      } else {
        resetAccounting()
      }
      val restartedMs = restartTrack()
      stopDeferred = false
      bridging = true
      awaitingFirstRealWrite = true
      stopRequested = false
      unitsWritten = 0
      feedStartedMs = restartedAtMs
      log?.invoke(
        "info",
        "keepalive",
        "Bridging: ${silence.label} across the $reason${if (wasHolding) " that ended a hold" else ""}; " +
          "the track was flushed and restarted in ${restartedMs}ms, silence starts at once and the head is left unread for ${headGraceMs}ms"
      )
      startFeeder()
    }
  }

  /**
   * Flushes the playing delegate and restarts it within milliseconds, so the output never goes
   * idle, and opens the head grace. Whatever silence was queued is gone with the flush. The lock
   * must be held. Returns how long the restart took.
   */
  private fun restartTrack(): Long {
    val startedMs = clock()
    delegate.pause() // AudioTrack.flush() is a no-op on a playing track
    delegate.flush()
    delegate.play()
    restartedAtMs = clock()
    headUnreadableUntilMs = restartedAtMs + headGraceMs
    headVerified = false
    silenceFramesWritten.set(0)
    pendingUnit = null
    pinnedSinceMs = Long.MIN_VALUE
    return restartedAtMs - startedMs
  }

  /**
   * Once the grace has passed, reads the head once and checks it against what was written since
   * the restart. Silence is kept about [targetAheadFrames] ahead and the HAL takes its time to
   * restart, so a counter that was reset is well behind the frames written; a stale one makes
   * media3 clamp the position to exactly those frames. Stale means another flush and restart
   * (with [allowRestart]; otherwise the caller waits for the feeder to do it), up to
   * [MAX_EXTRA_RESTARTS] times. Returns whether the head is usable.
   */
  private fun maybeVerifyHead(allowRestart: Boolean): Boolean {
    if (headVerified) return true
    if (clock() < headUnreadableUntilMs) return false
    synchronized(lock) {
      if (headVerified) return true
      if (!delegatePlaying) {
        headVerified = true // paused for real; there is nothing to verify
        return true
      }
      val writtenFrames = realFramesWritten - realFramesAtRestart + silenceFramesWritten.get()
      // Not enough queued yet to tell a reset counter from a stale one.
      if (writtenFrames < targetAheadFrames) return false
      val sinceRestartMs = clock() - restartedAtMs
      val writtenUs = framesToUs(writtenFrames)
      val headUs = delegate.getPositionUs()
      if (headUs < writtenUs - framesToUs(targetAheadFrames) / 2) {
        headVerified = true
        log?.invoke(
          "debug",
          "keepalive",
          "Playback head verified ${sinceRestartMs}ms after the restart: ${headUs / 1000}ms played of the ${writtenUs / 1000}ms written since" +
            if (restartAttempts > 0) " (after $restartAttempts extra restart${if (restartAttempts > 1) "s" else ""})" else ""
        )
        return true
      }
      if (restartAttempts >= MAX_EXTRA_RESTARTS) {
        headVerified = true
        headUnreliable = true
        log?.invoke(
          "warn",
          "keepalive",
          "Playback head still reports the frames written ${sinceRestartMs}ms after the restart; giving up after " +
            "$MAX_EXTRA_RESTARTS extra restarts, the clock may run ahead of the audio until the next seek"
        )
        return true
      }
      if (!allowRestart) return false
      restartAttempts++
      val restartedMs = restartTrack()
      log?.invoke(
        "warn",
        "keepalive",
        "Playback head stale ${sinceRestartMs}ms after the flush: it reports the ${writtenUs / 1000}ms written rather than what " +
          "has played, so the HAL had not reset its counter when it was first read; flushed and restarted again in " +
          "${restartedMs}ms (extra restart $restartAttempts of $MAX_EXTRA_RESTARTS)"
      )
      return false
    }
  }

  /** Starts a hold. The lock must be held. */
  private fun engageHold(note: String?) {
    positionAtPauseUs = getPositionUs()
    holding = true
    stopRequested = false
    feedStartedMs = clock()
    unitsWritten = 0
    val queued = queuedFrames()
    log?.invoke(
      "info",
      "keepalive",
      "Engaged: ${silence.label}; ~${framesToUs(queued) / 1000}ms of real audio queued, " +
        "silence starts once that drains to ${targetAheadFrames * 1000 / sampleRate}ms" +
        if (note != null) " ($note)" else ""
    )
    startFeeder()
  }

  private fun startFeeder() {
    if (!spawnFeederThread) return
    feeder = Thread({ runFeeder() }, "plezy-keepalive").apply {
      priority = Thread.MAX_PRIORITY
      start()
    }
  }

  /** The lock must be held. */
  private fun forwardStop() {
    stopDeferred = false
    delegateStopped = true
    delegatePlaying = false
    delegate.stop()
  }

  /** The lock must be held. */
  private fun resetAccounting() {
    realFramesWritten = 0
    silenceFramesWritten.set(0)
    framesPerAccessUnit = 0
    lastReportedUs = Long.MIN_VALUE
    positionAtPauseUs = Long.MIN_VALUE
    pendingUnit = null
    awaitingFirstRealWrite = false
    clockBaseUs = 0L
    realFramesAtRestart = 0L
    restartAttempts = 0
    headUnreliable = false
    pinnedSinceMs = Long.MIN_VALUE
  }

  /**
   * Ends a hold or bridge and waits for the feeder to finish its unit; returns whether one was
   * active.
   */
  private fun stopFeeder(reason: String): Boolean {
    val thread: Thread?
    val wasBridging: Boolean
    synchronized(lock) {
      if (!holding && !bridging) return false
      wasBridging = bridging
      stopRequested = true
      thread = feeder
      feeder = null
    }
    thread?.join(1_000L)
    // Without a feeder thread (tests) the partial unit is completed here, so nothing real can be
    // written between the halves of a silence unit.
    if (thread == null) while (pendingUnit != null && feedOnce()) Unit
    synchronized(lock) {
      holding = false
      bridging = false
      positionAtPauseUs = Long.MIN_VALUE
    }
    val elapsedMs = clock() - feedStartedMs
    val silenceMs = framesToUs(silenceFramesWritten.get()) / 1000
    if (wasBridging) {
      log?.invoke(
        "info",
        "keepalive",
        "Bridge ended ($reason) after ${elapsedMs}ms: wrote $unitsWritten units, ${silenceMs}ms of silence queued ahead of the new position" +
          if (!delegatePlaying) "; the track had been paused for real" else ""
      )
    } else {
      log?.invoke(
        "info",
        "keepalive",
        "Released ($reason) after ${elapsedMs}ms: wrote $unitsWritten units this hold, " +
          "${silenceMs}ms of silence since the track opened" +
          if (!delegatePlaying) "; the track had been paused for real" else ""
      )
    }
    return true
  }

  private fun runFeeder() {
    var lastHeadUs = if (headVerified) delegate.getPositionUs() else Long.MIN_VALUE
    var headCheckedAtMs = clock()
    var stallLogged = false
    try {
      while (true) {
        if (stopRequested && pendingUnit == null) return
        if (bridging && pendingUnit == null && clock() - feedStartedMs >= bridgeTimeoutMs) {
          pauseForReal("nobody came back for the bridge in ${bridgeTimeoutMs}ms")
          return
        }
        val wrote = feedOnce()
        if (!delegatePlaying) return // the write failed and the track was paused for real
        if (!wrote) Thread.sleep(FEED_POLL_MS)
        // A head that stops moving while silence is accepted means the platform is dropping the
        // frames (a DTS frame too large for its IEC burst did exactly this): the receiver loses the
        // stream and, worse, the subtracted silence never played, so the clock falls behind.
        val now = clock()
        if (!headVerified) {
          lastHeadUs = Long.MIN_VALUE
          headCheckedAtMs = now
        } else if (now - headCheckedAtMs >= HEAD_STALL_CHECK_MS) {
          val headUs = delegate.getPositionUs()
          if (lastHeadUs != Long.MIN_VALUE && headUs == lastHeadUs && unitsWritten > 0 && !stallLogged) {
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
    } catch (e: RuntimeException) {
      // Nothing thrown on this thread may reach the process.
      broken = true
      pauseForReal("the feeder failed (${e.javaClass.simpleName}: ${e.message})")
    }
  }

  /** Feeder-only: gives up on keeping the track alive and pauses it for real. */
  private fun pauseForReal(reason: String) {
    synchronized(lock) {
      if (!delegatePlaying) return
      delegatePlaying = false
      delegate.pause()
    }
    log?.invoke("warn", "keepalive", "Paused the track for real: $reason")
  }

  /**
   * One feeder step: completes a partial unit, or writes a fresh one when the queue is short.
   * Returns whether anything was written. On a write failure the delegate is paused for real
   * and the feeding ends.
   */
  fun feedOnce(): Boolean {
    if (!delegatePlaying) return false
    maybeVerifyHead(allowRestart = true)
    if (!delegatePlaying) return false
    val unit = pendingUnit ?: run {
      if (stopRequested) return false
      if (queuedFrames() >= targetAheadFrames) return false
      ByteBuffer.wrap(silence.unit)
    }
    val before = unit.remaining()
    val handled = try {
      delegate.write(unit, 1, 0L)
    } catch (e: AudioOutput.WriteException) {
      pendingUnit = null
      broken = true
      pauseForReal("silence write failed (${e.errorCode})")
      return false
    } catch (e: RuntimeException) {
      // A track that dies under the feeder (the HDMI link renegotiated, say) makes media3's output
      // tell its provider to reset the audio capabilities before it throws its WriteException, and
      // the provider insists on the playback thread: here that is an IllegalStateException, which
      // left uncaught would take the process down. The track is given up; the next real write, on
      // the playback thread, hits the same dead track and media3 recovers in its own way.
      pendingUnit = null
      broken = true
      pauseForReal("silence write failed (${e.javaClass.simpleName}: ${e.message})")
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

  /**
   * Frames written since the last restart (real and silence) that the playback head has not
   * reached. While the head is unreadable it is taken to have run at full rate since the restart,
   * which it cannot have (the HAL takes a while to restart), so the queue is only ever deeper.
   */
  private fun queuedFrames(): Long {
    val headFrames = if (headVerified) usToFrames(delegate.getPositionUs()) else usToFrames((clock() - restartedAtMs) * 1_000L)
    return realFramesWritten - realFramesAtRestart + silenceFramesWritten.get() - headFrames
  }

  private fun framesToUs(frames: Long): Long = frames * 1_000_000L / sampleRate
  private fun usToFrames(us: Long): Long = if (us <= 0L) 0L else us * sampleRate / 1_000_000L

  override fun addListener(listener: AudioOutput.Listener) {
    listeners.add(listener)
    delegate.addListener(listener)
  }

  override fun removeListener(listener: AudioOutput.Listener) {
    listeners.remove(listener)
    delegate.removeListener(listener)
  }

  override fun setVolume(volume: Float) = delegate.setVolume(volume)
  override fun isOffloadedPlayback() = delegate.isOffloadedPlayback()
  override fun getAudioSessionId() = delegate.getAudioSessionId()
  override fun getSampleRate() = delegate.getSampleRate()
  override fun getBufferSizeInFrames() = delegate.getBufferSizeInFrames()
  override fun getPlaybackParameters() = delegate.getPlaybackParameters()
  override fun isStalled() = delegate.isStalled()
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
    const val BRIDGE_TIMEOUT_MS = 30_000L
    const val HEAD_GRACE_MS = 150L
    const val MAX_EXTRA_RESTARTS = 3
    const val PINNED_CLOCK_MS = 1_000L
  }
}
