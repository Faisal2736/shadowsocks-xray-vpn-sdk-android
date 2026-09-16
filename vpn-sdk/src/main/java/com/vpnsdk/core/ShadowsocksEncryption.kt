package com.vpnsdk.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.modes.AEADBlockCipher
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shadowsocks AEAD encryption implementation
 * Supports: aes-256-gcm, aes-128-gcm, chacha20-ietf-poly1305
 * 
 * Note: This is a simplified implementation. For production, use shadowsocks-rust library
 * which provides optimized native encryption.
 */
class ShadowsocksEncryption(
    private val method: String,
    private val password: String
) {
    companion object {
        private const val TAG = "ShadowsocksEncryption"
        private const val SALT_LENGTH = 32
        private const val TAG_LENGTH = 16
        private const val IV_LENGTH_12 = 12
        private const val IV_LENGTH_16 = 16
    }
    
    private val key: ByteArray
    private val random = SecureRandom()
    
    init {
        // Add BouncyCastle provider if not already added
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        
        // Derive key from password using EVP_BytesToKey (simplified)
        key = deriveKey(password, method)
        Log.d(TAG, "Initialized encryption with method: $method, key length: ${key.size}")
    }
    
    /**
     * Derive encryption key from password
     * Uses simplified EVP_BytesToKey algorithm
     */
    private fun deriveKey(password: String, method: String): ByteArray {
        val keyLength = when {
            method.contains("256") -> 32
            method.contains("128") -> 16
            else -> 32 // Default to 256
        }
        
        val md = MessageDigest.getInstance("MD5")
        var hash = md.digest(password.toByteArray(Charsets.UTF_8))
        val key = ByteArray(keyLength)
        
        var i = 0
        while (i < keyLength) {
            if (i < hash.size) {
                key[i] = hash[i]
            } else {
                // Extend hash if needed
                hash = md.digest(hash)
                key[i] = hash[0]
            }
            i++
        }
        
        return key
    }
    
    /**
     * Encrypt data using AEAD cipher
     */
    suspend fun encrypt(data: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            when {
                method.contains("aes-256-gcm") || method.contains("aes-128-gcm") -> {
                    encryptAESGCM(data)
                }
                method.contains("chacha20") -> {
                    encryptChaCha20Poly1305(data)
                }
                else -> {
                    Log.w(TAG, "Unsupported method: $method, falling back to AES-256-GCM")
                    encryptAESGCM(data)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }
    
    /**
     * Decrypt data using AEAD cipher
     */
    suspend fun decrypt(encrypted: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            when {
                method.contains("aes-256-gcm") || method.contains("aes-128-gcm") -> {
                    decryptAESGCM(encrypted)
                }
                method.contains("chacha20") -> {
                    decryptChaCha20Poly1305(encrypted)
                }
                else -> {
                    Log.w(TAG, "Unsupported method: $method, falling back to AES-256-GCM")
                    decryptAESGCM(encrypted)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }
    
    /**
     * Encrypt using AES-GCM (Shadowsocks AEAD format)
     * Format: [Salt][Encrypted Payload with Tag]
     * For AEAD: Salt is 32 bytes, IV is derived from key and salt
     */
    private fun encryptAESGCM(data: ByteArray): ByteArray? {
        try {
            // Generate random salt (32 bytes for Shadowsocks AEAD)
            val salt = ByteArray(SALT_LENGTH)
            random.nextBytes(salt)
            
            // Derive subkey from main key and salt (simplified - in production use HKDF)
            val subkey = deriveSubkey(key, salt)
            
            // Generate IV (12 bytes for GCM)
            val iv = ByteArray(IV_LENGTH_12)
            random.nextBytes(iv)
            
            // Create cipher
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(subkey, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            
            // Encrypt (includes authentication tag)
            val encrypted = cipher.doFinal(data)
            
            // Shadowsocks AEAD format: [Salt][IV][Encrypted Data with Tag]
            val result = ByteArray(salt.size + iv.size + encrypted.size)
            System.arraycopy(salt, 0, result, 0, salt.size)
            System.arraycopy(iv, 0, result, salt.size, iv.size)
            System.arraycopy(encrypted, 0, result, salt.size + iv.size, encrypted.size)
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM encryption failed", e)
            return null
        }
    }
    
    /**
     * Derive subkey from key and salt (simplified HKDF)
     * In production, use proper HKDF-SHA1 or HKDF-SHA256
     */
    private fun deriveSubkey(key: ByteArray, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(key)
        return md.digest().copyOf(key.size)
    }
    
    /**
     * Decrypt using AES-GCM (Shadowsocks AEAD format)
     */
    private fun decryptAESGCM(encrypted: ByteArray): ByteArray? {
        try {
            if (encrypted.size < SALT_LENGTH + IV_LENGTH_12 + TAG_LENGTH) {
                Log.w(TAG, "Encrypted data too short: ${encrypted.size}")
                return null
            }
            
            // Extract salt, IV, and encrypted data with tag
            val salt = ByteArray(SALT_LENGTH)
            System.arraycopy(encrypted, 0, salt, 0, SALT_LENGTH)
            
            val iv = ByteArray(IV_LENGTH_12)
            System.arraycopy(encrypted, SALT_LENGTH, iv, 0, IV_LENGTH_12)
            
            val ciphertext = ByteArray(encrypted.size - SALT_LENGTH - IV_LENGTH_12)
            System.arraycopy(encrypted, SALT_LENGTH + IV_LENGTH_12, ciphertext, 0, ciphertext.size)
            
            // Derive subkey (must match encryption)
            val subkey = deriveSubkey(key, salt)
            
            // Create cipher
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(subkey, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            
            // Decrypt (verifies authentication tag)
            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM decryption failed", e)
            return null
        }
    }
    
    /**
     * Encrypt using ChaCha20-Poly1305 (Shadowsocks AEAD format)
     * Format: [Salt][Encrypted Payload with Tag]
     */
    private fun encryptChaCha20Poly1305(data: ByteArray): ByteArray? {
        return try {
            // Generate random salt (32 bytes for Shadowsocks AEAD)
            val salt = ByteArray(SALT_LENGTH)
            random.nextBytes(salt)
            
            // Derive subkey from main key and salt
            val subkey = deriveSubkey(key, salt)
            
            // Generate nonce (12 bytes for ChaCha20-Poly1305)
            val nonce = ByteArray(12)
            random.nextBytes(nonce)
            
            // Create ChaCha20-Poly1305 cipher
            // ChaCha20Poly1305 uses no-arg constructor, then initialize with AEADParameters
            val cipher = ChaCha20Poly1305()
            val keyParam = KeyParameter(subkey)
            val aeadParams = AEADParameters(keyParam, TAG_LENGTH * 8, nonce, null)
            cipher.init(true, aeadParams)
            
            // Encrypt
            val output = ByteArray(cipher.getOutputSize(data.size))
            var len = cipher.processBytes(data, 0, data.size, output, 0)
            len += cipher.doFinal(output, len)
            
            // Shadowsocks AEAD format: [Salt][Nonce][Encrypted Data with Tag]
            val result = ByteArray(salt.size + nonce.size + len)
            System.arraycopy(salt, 0, result, 0, salt.size)
            System.arraycopy(nonce, 0, result, salt.size, nonce.size)
            System.arraycopy(output, 0, result, salt.size + nonce.size, len)
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20-Poly1305 encryption failed", e)
            null
        }
    }
    
    /**
     * Decrypt using ChaCha20-Poly1305 (Shadowsocks AEAD format)
     */
    private fun decryptChaCha20Poly1305(encrypted: ByteArray): ByteArray? {
        return try {
            if (encrypted.size < SALT_LENGTH + 12 + TAG_LENGTH) {
                Log.w(TAG, "Encrypted data too short: ${encrypted.size}")
                return null
            }
            
            // Extract salt, nonce, and encrypted data with tag
            val salt = ByteArray(SALT_LENGTH)
            System.arraycopy(encrypted, 0, salt, 0, SALT_LENGTH)
            
            val nonce = ByteArray(12)
            System.arraycopy(encrypted, SALT_LENGTH, nonce, 0, 12)
            
            val ciphertext = ByteArray(encrypted.size - SALT_LENGTH - 12)
            System.arraycopy(encrypted, SALT_LENGTH + 12, ciphertext, 0, ciphertext.size)
            
            // Derive subkey (must match encryption)
            val subkey = deriveSubkey(key, salt)
            
            // Create ChaCha20-Poly1305 cipher
            // ChaCha20Poly1305 uses no-arg constructor, then initialize with AEADParameters
            val cipher = ChaCha20Poly1305()
            val keyParam = KeyParameter(subkey)
            val aeadParams = AEADParameters(keyParam, TAG_LENGTH * 8, nonce, null)
            cipher.init(false, aeadParams)
            
            // Decrypt
            val output = ByteArray(cipher.getOutputSize(ciphertext.size))
            var len = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
            len += cipher.doFinal(output, len)
            
            return output.copyOf(len)
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20-Poly1305 decryption failed", e)
            null
        }
    }
}

