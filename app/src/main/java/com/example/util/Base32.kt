package com.example.util

import java.util.Locale

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val CHAR_MAP = ALPHABET.withIndex().associate { it.value to it.index }

    fun decode(base32: String): ByteArray {
        val cleaned = base32.uppercase(Locale.ROOT).replace("[^A-Z2-7]".toRegex(), "")
        if (cleaned.isEmpty()) return ByteArray(0)
        val result = ByteArray((cleaned.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        var count = 0
        for (char in cleaned) {
            val valIndex = CHAR_MAP[char] ?: continue
            buffer = (buffer shl 5) or valIndex
            bitsLeft += 5
            if (bitsLeft >= 8) {
                result[count++] = (buffer shr (bitsLeft - 8)).toByte()
                bitsLeft -= 8
            }
        }
        return result.copyOf(count)
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(ALPHABET[index])
        }
        return sb.toString()
    }
}
