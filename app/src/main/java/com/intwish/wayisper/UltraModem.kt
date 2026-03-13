package com.intwish.wayisper

import kotlin.math.PI
import kotlin.math.sin

class UltraModem {

    val sampleRate = 44100
    val bitDurationMs = 80 
    
    // Default Ultrasonic
    var freq0 = 18500.0 
    var freq1 = 19500.0

    fun setDebugMode(enabled: Boolean) {
        if (enabled) {
            freq0 = 1000.0 // Audible 1kHz
            freq1 = 2000.0 // Audible 2kHz
        } else {
            freq0 = 18500.0
            freq1 = 19500.0
        }
    }

    fun generateTone(frequency: Double, durationMs: Int): ShortArray {
        val numSamples = (durationMs * sampleRate) / 1000
        val buffer = ShortArray(numSamples)
        val amplitude = 30000.0 
        val rampSamples = (10 * sampleRate) / 1000 

        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * i.toDouble() * frequency / sampleRate
            val sineValue = sin(angle)
            
            val ramp = when {
                i < rampSamples -> i.toDouble() / rampSamples
                i > numSamples - rampSamples -> (numSamples - i).toDouble() / rampSamples
                else -> 1.0
            }
            
            buffer[i] = (sineValue * amplitude * ramp).toInt().toShort()
        }
        return buffer
    }

    fun stringToBits(text: String): List<Int> {
        val preamble = listOf(1, 0, 1, 0, 1)
        val postamble = listOf(0, 1, 0, 1, 0)
        val bits = mutableListOf<Int>()

        bits.addAll(preamble)

        val bytes = text.toByteArray(Charsets.UTF_8)
        val checksum = calculateChecksum(bytes)
        
        for (byte in bytes) {
            appendByteAsBits(bits, byte)
        }
        
        appendByteAsBits(bits, checksum)
        bits.addAll(postamble)
        return bits
    }

    private fun appendByteAsBits(bits: MutableList<Int>, byte: Byte) {
        val b = byte.toInt()
        for (i in 7 downTo 0) {
            bits.add((b shr i) and 1)
        }
    }

    fun bitsToString(bits: List<Int>): String? {
        if (bits.size < 8 || bits.size % 8 != 0) return null
        
        val bytes = ByteArray(bits.size / 8)
        for (i in bytes.indices) {
            var byteValue = 0
            for (j in 0 until 8) {
                byteValue = (byteValue shl 1) or bits[i * 8 + j]
            }
            bytes[i] = byteValue.toByte()
        }
        
        if (bytes.isEmpty()) return null
        
        val data = bytes.copyOfRange(0, bytes.size - 1)
        val receivedChecksum = bytes.last()
        val calculatedChecksum = calculateChecksum(data)
        
        return if (receivedChecksum == calculatedChecksum) {
            try {
                String(data, Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun calculateChecksum(data: ByteArray): Byte {
        var checksum: Byte = 0
        for (b in data) {
            checksum = (checksum.toInt() xor b.toInt()).toByte()
        }
        return checksum
    }
}