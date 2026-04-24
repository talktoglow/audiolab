package net.siteed.audiostudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue
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
class AudioPlaybackManager {

    companion object {
        private const val CLASS_NAME = "AudioPlaybackManager"
        private const val DEFAULT_SAMPLE_RATE = 24000 // Gemini Live output rate
    }

    private var audioTrack: AudioTrack? = null
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var bufferSizeInBytes: Int = 0

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val writerRunning = AtomicBoolean(false)
    private var writerThread: Thread? = null

    @Volatile private var isInitialized: Boolean = false

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

        audioTrack?.play()
        startWriterThread()
        isInitialized = true
        LogUtils.d(CLASS_NAME, "Playback initialized at ${sampleRate}Hz (buffer=${bufferSizeInBytes}B)")
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
        LogUtils.d(CLASS_NAME, "Playback cleaned up")
    }

    private fun startWriterThread() {
        if (writerRunning.get()) return
        writerRunning.set(true)

        writerThread = Thread({
            while (writerRunning.get()) {
                val chunk = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                val track = audioTrack ?: break
                if (track.state != AudioTrack.STATE_INITIALIZED) break

                var offset = 0
                while (offset < chunk.size && writerRunning.get()) {
                    val written = try {
                        track.write(chunk, offset, chunk.size - offset)
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
