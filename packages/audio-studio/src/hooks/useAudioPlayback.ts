/**
 * Audio playback hook that uses audio-studio's native audio engine.
 *
 * Designed for low-latency PCM streaming (e.g. Gemini Live) where the same
 * native audio session must own both recording and playback so platform-level
 * acoustic echo cancellation (VoiceProcessingIO on iOS, VOICE_COMMUNICATION +
 * AcousticEchoCanceler on Android) can subtract the speaker signal from the
 * mic input.
 */
import { useRef, useCallback, useState } from 'react'
import { Platform } from 'react-native'
import AudioStudioModule from '../AudioStudioModule'
import {
    PlaybackConfig,
    UseAudioPlaybackReturn,
} from '../AudioStudio.types'

const DEFAULT_SAMPLE_RATE = 24000 // Gemini outputs 24kHz audio
const DEFAULT_GAIN = 1.0

// Estimated duration of audio chunk based on sample rate
const CHUNK_DURATION_MS = 100

// Extra buffer after audio finishes before marking as not playing
const POST_PLAYBACK_BUFFER_MS = 500

export function useAudioPlayback(): UseAudioPlaybackReturn {
    const [isPlaying, setIsPlaying] = useState(false)
    const isInitializedRef = useRef(false)
    const initFailedRef = useRef(false)
    const sampleRateRef = useRef(DEFAULT_SAMPLE_RATE)
    const playbackStateCallbackRef = useRef<
        ((isPlaying: boolean) => void) | null
    >(null)
    const playbackTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(
        null
    )
    const lastChunkTimeRef = useRef<number>(0)
    const queueSizeRef = useRef(0)

    const onPlaybackStateChange = useCallback(
        (callback: (isPlaying: boolean) => void) => {
            playbackStateCallbackRef.current = callback
        },
        []
    )

    const setPlayingState = useCallback((playing: boolean) => {
        setIsPlaying((prev) => {
            if (prev !== playing) {
                playbackStateCallbackRef.current?.(playing)
                return playing
            }
            return prev
        })
    }, [])

    const schedulePlaybackEndCheck = useCallback(() => {
        if (playbackTimeoutRef.current) {
            clearTimeout(playbackTimeoutRef.current)
        }

        const estimatedDuration =
            queueSizeRef.current * CHUNK_DURATION_MS + POST_PLAYBACK_BUFFER_MS

        playbackTimeoutRef.current = setTimeout(() => {
            const timeSinceLastChunk = Date.now() - lastChunkTimeRef.current
            if (timeSinceLastChunk >= POST_PLAYBACK_BUFFER_MS) {
                setPlayingState(false)
            } else {
                schedulePlaybackEndCheck()
            }
        }, estimatedDuration)
    }, [setPlayingState])

    const initialize = useCallback((config?: PlaybackConfig): boolean => {
        if (isInitializedRef.current) return true
        if (initFailedRef.current) return false

        if (Platform.OS === 'web') {
            initFailedRef.current = true
            return false
        }

        try {
            const sampleRate = config?.sampleRate ?? DEFAULT_SAMPLE_RATE
            const gain = config?.gain ?? DEFAULT_GAIN

            sampleRateRef.current = sampleRate

            AudioStudioModule.initializePlayback(sampleRate)
            AudioStudioModule.setPlaybackVolume(gain)

            isInitializedRef.current = true
            initFailedRef.current = false
            return true
        } catch {
            initFailedRef.current = true
            return false
        }
    }, [])

    const playChunk = useCallback(
        (base64Audio: string) => {
            if (!isInitializedRef.current && !initialize()) return
            try {
                AudioStudioModule.playBuffer(base64Audio, sampleRateRef.current)
                queueSizeRef.current += 1
                lastChunkTimeRef.current = Date.now()
                setPlayingState(true)
                schedulePlaybackEndCheck()
            } catch {
                // Silently drop on transient errors; caller can re-init.
            }
        },
        [initialize, schedulePlaybackEndCheck, setPlayingState]
    )

    const clearQueue = useCallback(() => {
        if (!isInitializedRef.current) return
        try {
            AudioStudioModule.clearPlaybackQueue()
            queueSizeRef.current = 0
            if (playbackTimeoutRef.current) {
                clearTimeout(playbackTimeoutRef.current)
                playbackTimeoutRef.current = null
            }
            setPlayingState(false)
        } catch {
            // ignore
        }
    }, [setPlayingState])

    const cleanup = useCallback(() => {
        if (!isInitializedRef.current) return
        try {
            AudioStudioModule.cleanupPlayback()
        } catch {
            // ignore
        }
        if (playbackTimeoutRef.current) {
            clearTimeout(playbackTimeoutRef.current)
            playbackTimeoutRef.current = null
        }
        isInitializedRef.current = false
        initFailedRef.current = false
        queueSizeRef.current = 0
        setPlayingState(false)
    }, [setPlayingState])

    return {
        initialize,
        playChunk,
        clearQueue,
        cleanup,
        isPlaying,
        onPlaybackStateChange,
    }
}
