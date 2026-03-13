package com.intwish.wayisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.sqrt

class UltrasonicListener(private val callback: OnMessageReceived) {

    interface OnMessageReceived {
        fun onMessage(message: String)
    }

    private val sampleRate = 44100
    private val bufferSizeSamples = 2048 
    private val modem = UltraModem()
    
    private val preamble = listOf(1, 0, 1, 0, 1)
    private val postamble = listOf(0, 1, 0, 1, 0)

    private var job: Job? = null

    enum class State { IDLE, COLLECTING }
    private var currentState = State.IDLE
    private val bitBuffer = mutableListOf<Int>()

    fun setDebugMode(enabled: Boolean) {
        modem.setDebugMode(enabled)
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        
        job = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize.coerceAtLeast(bufferSizeSamples * 4)
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) return@launch
            audioRecord.startRecording()

            val audioBuffer = ShortArray(bufferSizeSamples)
            val fftBuffer = DoubleArray(bufferSizeSamples)
            val fft = DoubleFFT_1D(bufferSizeSamples.toLong())
            
            var noiseFloorAverage = 0.0

            while (isActive) {
                val read = audioRecord.read(audioBuffer, 0, bufferSizeSamples)
                if (read > 0) {
                    for (i in 0 until bufferSizeSamples) {
                        fftBuffer[i] = audioBuffer[i].toDouble()
                    }
                    
                    fft.realForward(fftBuffer)
                    
                    val freq0Idx = (modem.freq0 * bufferSizeSamples / sampleRate).toInt()
                    val freq1Idx = (modem.freq1 * bufferSizeSamples / sampleRate).toInt()
                    
                    val mag0 = getMagnitude(fftBuffer, freq0Idx)
                    val mag1 = getMagnitude(fftBuffer, freq1Idx)
                    
                    val noiseStartIdx = ((modem.freq0 - 1000).coerceAtLeast(0.0) * bufferSizeSamples / sampleRate).toInt()
                    val noiseEndIdx = ((modem.freq1 + 1000).coerceAtMost(sampleRate / 2.0) * bufferSizeSamples / sampleRate).toInt()
                    var currentNoiseSum = 0.0
                    var count = 0
                    for (i in noiseStartIdx..noiseEndIdx) {
                        if (abs(i - freq0Idx) > 2 && abs(i - freq1Idx) > 2) {
                            currentNoiseSum += getMagnitude(fftBuffer, i)
                            count++
                        }
                    }
                    val currentNoiseFloor = if (count > 0) currentNoiseSum / count else 0.0
                    noiseFloorAverage = noiseFloorAverage * 0.8 + currentNoiseFloor * 0.2

                    val threshold = (noiseFloorAverage * 1.5).coerceAtLeast(100.0)
                    
                    if (mag0 > threshold && mag0 > mag1 * 1.2) {
                        processBit(0)
                    } else if (mag1 > threshold && mag1 > mag0 * 1.2) {
                        processBit(1)
                    } else {
                        processBit(-1)
                    }
                }
            }
            
            audioRecord.stop()
            audioRecord.release()
        }
    }

    private fun getMagnitude(fftData: DoubleArray, index: Int): Double {
        val n = fftData.size
        if (index < 0 || index >= n / 2) return 0.0
        val re = fftData[2 * index]
        val im = fftData[2 * index + 1]
        return sqrt(re * re + im * im)
    }

    private var lastBit = -1
    private var bitRepeatCount = 0
    private val SAMPLES_PER_BIT = 1.7 

    private fun processBit(bit: Int) {
        if (bit == lastBit && bit != -1) {
            bitRepeatCount++
        } else {
            if (lastBit != -1) {
                val numBits = Math.round(bitRepeatCount / SAMPLES_PER_BIT).toInt().coerceAtLeast(1)
                repeat(numBits) {
                    handleValidatedBit(lastBit)
                }
            }
            lastBit = bit
            bitRepeatCount = if (bit == -1) 0 else 1
        }
    }

    private fun handleValidatedBit(bit: Int) {
        bitBuffer.add(bit)
        
        when (currentState) {
            State.IDLE -> {
                if (bitBuffer.size >= preamble.size) {
                    val lastBits = bitBuffer.takeLast(preamble.size)
                    if (lastBits == preamble) {
                        currentState = State.COLLECTING
                        bitBuffer.clear()
                    } else if (bitBuffer.size > 20) {
                        bitBuffer.removeAt(0)
                    }
                }
            }
            State.COLLECTING -> {
                if (bitBuffer.size >= postamble.size) {
                    val lastBits = bitBuffer.takeLast(postamble.size)
                    if (lastBits == postamble) {
                        val dataBits = bitBuffer.dropLast(postamble.size)
                        if (dataBits.isNotEmpty() && dataBits.size % 8 == 0) {
                            val message = modem.bitsToString(dataBits)
                            if (message != null) {
                                callback.onMessage(message)
                            }
                        }
                        currentState = State.IDLE
                        bitBuffer.clear()
                    }
                }
                if (bitBuffer.size > 1000) {
                    currentState = State.IDLE
                    bitBuffer.clear()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}