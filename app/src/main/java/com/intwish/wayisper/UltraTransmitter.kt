package com.intwish.wayisper

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UltraTransmitter {

    private val modem = UltraModem()
    private val transmissionMutex = Mutex()

    fun setDebugMode(enabled: Boolean) {
        modem.setDebugMode(enabled)
    }

    suspend fun transmit(text: String) = withContext(Dispatchers.IO) {
        transmissionMutex.withLock {
            val bits = modem.stringToBits(text)
            val sampleRate = modem.sampleRate
            val bitDurationMs = modem.bitDurationMs
            
            val samplesPerBit = (bitDurationMs * sampleRate) / 1000
            val totalSamples = bits.size * samplesPerBit
            val fullBuffer = ShortArray(totalSamples)
            
            var offset = 0
            for (bit in bits) {
                val freq = if (bit == 0) modem.freq0 else modem.freq1
                val tone = modem.generateTone(freq, bitDurationMs)
                
                val length = minOf(tone.size, samplesPerBit, fullBuffer.size - offset)
                if (length > 0) {
                    System.arraycopy(tone, 0, fullBuffer, offset, length)
                }
                offset += samplesPerBit
            }

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(fullBuffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(fullBuffer, 0, fullBuffer.size)
            audioTrack.play()
            
            val playTimeMs = (fullBuffer.size.toLong() * 1000) / sampleRate
            kotlinx.coroutines.delay(playTimeMs + 200)
            
            audioTrack.stop()
            audioTrack.release()
        }
    }
}