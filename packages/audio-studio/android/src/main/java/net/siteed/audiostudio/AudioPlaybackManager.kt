package net.siteed.audiostudio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming PCM16 audio playback for voice-mode use cases.
 *
 * This manager exists alongside [AudioRecorderManager] so a voice-chat client can
 * push assistant audio (e.g. Gemini Live PCM16 chunks) through the device speaker
 * while the recorder is capturing the user. Configuring the [AudioTrack] with
 * [AudioAttributes.USAGE_VOICE_COMMUNICATION] tells the platform to route through
 * the call audio path, which is the same path that VOICE_COMMUNICATION-source
 * recording + [android.media.audiofx.AcousticEchoCanceler] expect for AEC to work.
 *
 * The implementation is single-track (one [AudioTrack] reused for the lifetime of
 * a session) plus a writer thread draining a [LinkedBlockingQueue] of byte buffers.
 * Re-initializing on a sample-rate change is cheap and explicit (call [initialize]
 * again with the new rate).
 */
class AudioPlaybackManager(private val context: Context) {

    companion object {
        private const val CLASS_NAME = "AudioPlaybackManager"
        private const val DEFAULT_SAMPLE_RATE = 24000 // Gemini Live output rate
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioTrack: AudioTrack? = null
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var bufferSizeInBytes: Int = 0

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val writerRunning = AtomicBoolean(false)
    private var writerThread: Thread? = null

    @Volatile private var isInitialized: Boolean = false

    // Saved audio routing state so cleanup() can restore the device to the
    // mode + speakerphone preference the user had before voice mode started.
    private var savedAudioRouting: Boolean = false
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    @Suppress("DEPRECATION")
    private var previousSpeakerphone: Boolean = false

    /** True while the AudioTrack is in PLAYING state and the writer is draining the queue. */
    val isPlaybackActive: Boolean
        get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    /**
     * Creates the underlying [AudioTrack] sized for low-latency streaming and starts the
     * writer thread. Subsequent calls with the same [sampleRate] are no-ops; calls with a
     * different rate transparently re-create the track.
     */
    fun initialize(sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (isInitialized && this.sampleRate == sampleRate) {
            return
        }
        if (isInitialized) {
            // Sample rate changed — tear down and rebuild.
            cleanup()
        }

        this.sampleRate = sampleRate

        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuffer <= 0) {
            LogUtils.e(CLASS_NAME, "AudioTrack.getMinBufferSize returned $minBuffer for rate=$sampleRate")
            return
        }
        // Double the minimum to give some headroom for jittery PCM streams.
        bufferSizeInBytes = minBuffer * 2

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelConfig)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeInBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            LogUtils.e(CLASS_NAME, "AudioTrack failed to initialize")
            audioTrack?.release()
            audioTrack = null
            return
        }

        // Force voice-comm audio routing so the AudioTrack actually plays through
        // the loudspeaker (or BT/wired headset) instead of the earpiece, which is
        // where Android sends USAGE_VOICE_COMMUNICATION audio by default.
        routeForVoiceCommunication()

        audioTrack?.play()

        // Prime the track with ~60ms of silence before any real audio arrives.
        // Without this the first chunk plays into an empty AudioTrack buffer that's
        // already in PLAYING state, which (a) emits a "first underrun" glitch and
        // (b) may be clipped by the still-settling setCommunicationDevice routing
        // switch from earpiece → loudspeaker. The cost is a one-time ~60ms latency
        // at session start; per-chunk latency is unchanged.
        val primingMs = 60
        val primingBytes = (sampleRate * 2 /* 16-bit mono */ * primingMs / 1000)
        try {
            audioTrack?.write(ByteArray(primingBytes), 0, primingBytes)
        } catch (e: Exception) {
            LogUtils.w(CLASS_NAME, "silence priming write failed: ${e.message}")
        }

        startWriterThread()
        isInitialized = true
        LogUtils.d(CLASS_NAME, "Playback initialized at ${sampleRate}Hz (buffer=${bufferSizeInBytes}B, primed=${primingBytes}B)")
    }

    /**
     * Sets [AudioManager.MODE_IN_COMMUNICATION] and routes voice-communication audio to a
     * loud output device. Prefers an existing communication device (Bluetooth SCO, wired
     * headset) so the user's headphones still work, falling back to the built-in
     * loudspeaker. Without this, USAGE_VOICE_COMMUNICATION audio plays from the earpiece
     * and is barely audible.
     *
     * Saves the prior mode + speakerphone state so [restoreAudioRouting] can put the
     * device back the way we found it.
     */
    private fun routeForVoiceCommunication() {
        if (!savedAudioRouting) {
            previousAudioMode = audioManager.mode
            @Suppress("DEPRECATION")
            previousSpeakerphone = audioManager.isSpeakerphoneOn
            savedAudioRouting = true
        }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            LogUtils.w(CLASS_NAME, "Failed to set MODE_IN_COMMUNICATION: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ uses setCommunicationDevice. setSpeakerphoneOn is a no-op for
            // non-system apps on these versions.
            val devices = audioManager.availableCommunicationDevices
            val preferred = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

            if (preferred != null) {
                val ok = audioManager.setCommunicationDevice(preferred)
                LogUtils.d(CLASS_NAME, "setCommunicationDevice(type=${preferred.type}) => $ok")
            } else {
                LogUtils.w(CLASS_NAME, "No communication device available; falling back to current routing")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            LogUtils.d(CLASS_NAME, "Forced speakerphone on (legacy API)")
        }
    }

    private fun restoreAudioRouting() {
        if (!savedAudioRouting) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = previousSpeakerphone
            }
            audioManager.mode = previousAudioMode
        } catch (e: Exception) {
            LogUtils.w(CLASS_NAME, "Failed to restore audio routing: ${e.message}")
        }
        savedAudioRouting = false
    }

    /**
     * Decodes [base64Audio] (little-endian Int16 PCM) and enqueues it for playback.
     * If the manager has not been initialized yet, this lazily initializes at [sampleRate].
     */
    fun playBuffer(base64Audio: String, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (!isInitialized || this.sampleRate != sampleRate) {
            initialize(sampleRate)
        }
        val track = audioTrack ?: return

        val bytes = try {
            Base64.decode(base64Audio, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            LogUtils.e(CLASS_NAME, "playBuffer: invalid base64 audio: ${e.message}")
            return
        }
        if (bytes.isEmpty()) return

        // Make sure the track is in PLAYING state — clearPlaybackQueue may have paused it.
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }

        queue.offer(bytes)
    }

    /**
     * Drops queued audio that has not yet been written to the track and flushes whatever
     * the AudioTrack has already buffered. The track stays alive so subsequent
     * [playBuffer] calls play immediately.
     */
    fun clearPlaybackQueue() {
        queue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            LogUtils.w(CLASS_NAME, "clearPlaybackQueue error: ${e.message}")
        }
    }

    /** Stops playback and clears any pending audio without releasing the track. */
    fun stopPlayback() {
        queue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            LogUtils.w(CLASS_NAME, "stopPlayback error: ${e.message}")
        }
    }

    /**
     * Sets the playback gain. AudioTrack accepts values in [0, AudioTrack.getMaxVolume()].
     * Values >1.0 amplify up to that ceiling; values outside the range are clamped.
     */
    fun setPlaybackVolume(volume: Float) {
        val track = audioTrack ?: return
        val max = AudioTrack.getMaxVolume()
        val clamped = volume.coerceIn(0f, max)
        track.setVolume(clamped)
    }

    /** Tears down the writer thread and releases the [AudioTrack]. */
    fun cleanup() {
        if (!isInitialized) return
        writerRunning.set(false)
        queue.clear()
        // Unblock the writer thread sitting in queue.take()
        writerThread?.interrupt()
        writerThread = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (e: Exception) {
            LogUtils.w(CLASS_NAME, "cleanup: stop error: ${e.message}")
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            LogUtils.w(CLASS_NAME, "cleanup: release error: ${e.message}")
        }
        audioTrack = null
        isInitialized = false
        restoreAudioRouting()
        LogUtils.d(CLASS_NAME, "Playback cleaned up")
    }

    private fun startWriterThread() {
        if (writerRunning.get()) return
        writerRunning.set(true)

        // Pre-allocated 20ms silence buffer — written when the queue is empty to keep the
        // AudioTrack buffer continuously fed. Without this the buffer drains between AI
        // turns, the speaker amp + AEC enter a "cold" state, and the first ~50–100ms of
        // the next utterance gets attenuated as the compressor/AEC re-adapt to speech.
        // Continuously writing silence keeps the audio path warm so each new utterance
        // plays at full volume from the very first sample. Size matches the poll timeout
        // so we never write faster than the AudioTrack consumes.
        val silenceMs = 20
        val silenceBytes = ByteArray(sampleRate * 2 /* 16-bit mono */ * silenceMs / 1000)

        writerThread = Thread({
            while (writerRunning.get()) {
                val track = audioTrack ?: break
                if (track.state != AudioTrack.STATE_INITIALIZED) break

                val chunk = try {
                    queue.poll(silenceMs.toLong(), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }

                val (data, length) = if (chunk != null) chunk to chunk.size
                                     else silenceBytes to silenceBytes.size

                var offset = 0
                while (offset < length && writerRunning.get()) {
                    val written = try {
                        track.write(data, offset, length - offset)
                    } catch (e: Exception) {
                        LogUtils.e(CLASS_NAME, "AudioTrack.write failed: ${e.message}")
                        -1
                    }
                    if (written < 0) break
                    offset += written
                }
            }
        }, "AudioPlaybackManager-writer").apply { start() }
    }
}
