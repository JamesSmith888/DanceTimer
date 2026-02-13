package com.example.dancetimer.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * 静默音频播放器 — 播放无声音频占据音频流
 *
 * 目的：让系统（尤其是 OPPO/ColorOS）认为 App 正在播放音乐，
 * 从而在锁屏时将音量键事件路由到我们的 MediaSession VolumeProvider。
 *
 * 播放的是零振幅的 PCM 数据（完全无声），不会发出任何声音。
 */
object SilentAudioPlayer {

    private const val TAG = "SilentAudioPlayer"
    private const val SAMPLE_RATE = 8000 // 最低采样率，节省资源
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playbackThread: Thread? = null

    /**
     * 开始播放静默音频
     */
    fun start() {
        if (isPlaying) return
        
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            
            audioTrack = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack?.play()
            isPlaying = true
            
            // 后台线程持续写入零数据
            playbackThread = Thread {
                val silence = ByteArray(bufferSize) // 全零 = 完全静默
                while (isPlaying) {
                    try {
                        audioTrack?.write(silence, 0, silence.size)
                    } catch (e: Exception) {
                        break
                    }
                }
            }.apply {
                name = "SilentAudioThread"
                isDaemon = true
                start()
            }
            
            Log.d(TAG, "静默音频已启动 (sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "启动静默音频失败", e)
            isPlaying = false
        }
    }

    /**
     * 停止播放静默音频
     */
    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "停止音频时异常", e)
        }
        audioTrack = null
        Log.d(TAG, "静默音频已停止")
    }
}
