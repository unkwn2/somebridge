package com.unkwn2.yandexhud.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.math.BigInteger
import java.security.*
import java.security.spec.RSAPublicKeySpec

object LicenseManager {
    private const val PREFS = "yandexhud_license"
    private const val KEY_PUB = "client_pub"
    private const val KEY_PRIV = "client_priv"
    private const val KEY_LICENSE = "license_str"

    // --- Developer's RSA-2048 Public Key (embedded in APK) ---
    // Used to verify that the license was signed by the developer.
    // Generate with: LicenseGenerator --genkey
    private val DEV_MODULUS = run {
        val raw = Base64.decode("tl3kc8O2aJ/ccGz9680lY8IOA1KlumFI13NEI5bTRNVJ4H1iewUOOiwNglBfHuGvfZiPffcNwrpK0PUrbghGZYVLuCveRfqzsu1U5QQvyCP+5LxRaXkXo8u4JzLyXmHHpvbezD3c94+C6UxNYIiYC0mET+RVjhmsnMnThKC8zOr35BrofcTcV37BEpX2p21yKGaa9qNMeM3ay05UqIynm/dr5iH+0ea1z76CHFqW1cFc3NgvNqY0cRLFH4VcA/vOqP8xXm3KxB8zjtHwvmsHI/pyM6CMWdJZtTEDlkQgtTCFjv5yDr0t88BtUrKjV0JDn5Pf4o4fh3JlSR2JoUSvgQ==", Base64.DEFAULT)
        BigInteger(1, raw)
    }
    private val DEV_EXPONENT = BigInteger.valueOf(65537L)
    private val devKeyFactory = KeyFactory.getInstance("RSA")
    private val devPublicKey = devKeyFactory.generatePublic(RSAPublicKeySpec(DEV_MODULUS, DEV_EXPONENT))

    fun getClientPublicKey(ctx: Context): String {
        val prefs = getPrefs(ctx)
        var pubB64 = prefs.getString(KEY_PUB, null)
        if (pubB64 != null) return pubB64

        // First launch: generate RSA-2048 key pair
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PUB, pubB64).putString(KEY_PRIV, privB64).apply()
        return pubB64
    }

    fun isLicenseValid(ctx: Context): Boolean {
        val prefs = getPrefs(ctx)
        val licenseStr = prefs.getString(KEY_LICENSE, null) ?: return false
        val clientPubB64 = prefs.getString(KEY_PUB, null) ?: return false
        return verifyLicenseStr(licenseStr, clientPubB64)
    }

    fun activate(ctx: Context, licenseStr: String): Boolean {
        val clientPubB64 = getClientPublicKey(ctx)
        if (!verifyLicenseStr(licenseStr, clientPubB64)) return false

        getPrefs(ctx).edit().putString(KEY_LICENSE, licenseStr).apply()
        return true
    }

    private fun verifyLicenseStr(licenseStr: String, clientPubB64: String): Boolean {
        val parts = licenseStr.split(".")
        if (parts.size != 2) return false
        val pubB64 = parts[0]
        val sigB64 = parts[1]
        if (pubB64.isBlank() || sigB64.isBlank()) return false

        val pubBytes: ByteArray
        val sigBytes: ByteArray
        val clientPubBytes: ByteArray
        try {
            pubBytes = Base64.decode(pubB64, Base64.DEFAULT)
            sigBytes = Base64.decode(sigB64, Base64.DEFAULT)
            clientPubBytes = Base64.decode(clientPubB64, Base64.DEFAULT)
        } catch (_: Exception) { return false }

        // Extracted public key must match the client's own key (compare bytes, not string encoding)
        if (!pubBytes.contentEquals(clientPubBytes)) return false

        // Verify signature with developer's public key
        return try {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(devPublicKey)
            sig.update(pubBytes)
            sig.verify(sigBytes)
        } catch (_: Exception) { false }
    }

    private fun getPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}