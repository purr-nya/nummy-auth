package com.example.util

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    fun encrypt(plainText: String, password: String): String {
        try {
            // Derive a 256-bit AES key securely using SHA-256
            val keyData = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
            val keySpec = SecretKeySpec(keyData, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    fun decrypt(encryptedText: String, password: String): String {
        try {
            val keyData = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
            val keySpec = SecretKeySpec(keyData, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.decode(encryptedText, Base64.NO_WRAP)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
