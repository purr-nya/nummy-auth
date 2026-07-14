package com.example.util

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpUtils {
    fun generateTotp(
        secretBase32: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        stepSeconds: Int = 30,
        digits: Int = 6
    ): String {
        try {
            val key = Base32.decode(secretBase32)
            if (key.isEmpty()) return "000000".take(digits)
            val counter = timeSeconds / stepSeconds
            val data = ByteBuffer.allocate(8).putLong(counter).array()

            val mac = Mac.getInstance("HmacSHA1")
            val keySpec = SecretKeySpec(key, "HmacSHA1")
            mac.init(keySpec)
            val hash = mac.doFinal(data)

            val offset = hash[hash.size - 1].toInt() and 0xF
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val otp = binary % 10.0.pow(digits.toDouble()).toInt()
            return otp.toString().padStart(digits, '0')
        } catch (e: Exception) {
            e.printStackTrace()
            return "000000".take(digits)
        }
    }

    /**
     * Parse otpauth:// URIs, e.g.:
     * otpauth://totp/Google:alice@gmail.com?secret=JBSWY3DPEHPK3PXP&issuer=Google
     */
    fun parseOtpAuthUri(uriString: String): Map<String, String>? {
        if (!uriString.startsWith("otpauth://", ignoreCase = true)) return null
        try {
            val cleanUri = uriString.trim()
            val typeAndLabelAndQuery = cleanUri.substring("otpauth://".length)
            val firstSlash = typeAndLabelAndQuery.indexOf('/')
            if (firstSlash == -1) return null
            val type = typeAndLabelAndQuery.substring(0, firstSlash)
            val labelAndQuery = typeAndLabelAndQuery.substring(firstSlash + 1)
            val firstQuestion = labelAndQuery.indexOf('?')
            
            val labelEncoded = if (firstQuestion == -1) labelAndQuery else labelAndQuery.substring(0, firstQuestion)
            val query = if (firstQuestion == -1) "" else labelAndQuery.substring(firstQuestion + 1)
            
            val label = java.net.URLDecoder.decode(labelEncoded, "UTF-8")
            
            val params = query.split('&').associate {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) {
                    java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8")
                } else {
                    it to ""
                }
            }
            
            val result = mutableMapOf<String, String>()
            result["type"] = type
            result["label"] = label
            result["secret"] = params["secret"] ?: ""
            result["issuer"] = params["issuer"] ?: ""
            result["algorithm"] = params["algorithm"] ?: "SHA1"
            result["digits"] = params["digits"] ?: "6"
            result["period"] = params["period"] ?: "30"
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
