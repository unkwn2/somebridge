package com.unkwn2.yandexhud.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object LocalAdb {
    private const val TAG = "LADB"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555

    // Command magics (little-endian ASCII)
    private const val A_CNXN = 0x4e584e43 // "CNXN"
    private const val A_AUTH = 0x48545541 // "AUTH"
    private const val A_OPEN = 0x4e45504f // "OPEN"
    private const val A_OKAY = 0x59414b4f // "OKAY"
    private const val A_WRTE = 0x45545257 // "WRTE"
    private const val A_CLSE = 0x45534c43 // "CLSE"
    private const val A_STLS = 0x534c5453 // "STLS"

    private const val ADB_AUTH_TOKEN = 1
    private const val ADB_AUTH_SIGNATURE = 2
    private const val ADB_AUTH_RSAPUBLICKEY = 3

    private const val VERSION = 0x01000000
    private const val MAXDATA = 256 * 1024

    // ASN.1 DigestInfo prefix for SHA-1 (PKCS#1 v1.5)
    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    private var privateKey: java.security.PrivateKey? = null
    private var publicKey: RSAPublicKey? = null
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    data class Result(val success: Boolean, val output: String = "", val error: String = "")

    fun init(ctx: android.content.Context): Boolean {
        try {
            loadOrCreateKeys(ctx)
            val s = Socket()
            s.connect(InetSocketAddress(ADB_HOST, ADB_PORT), 5000)
            s.soTimeout = 60000
            socket = s
            input = DataInputStream(s.getInputStream())
            output = DataOutputStream(s.getOutputStream())

            val hostBytes = "host::\u0000".toByteArray()
            send(A_CNXN, VERSION, MAXDATA, hostBytes)
            return doAuth()
        } catch (e: Exception) {
            Logger.e(TAG, "init: ${e.message}")
            disconnect()
            return false
        }
    }

    private fun loadOrCreateKeys(ctx: android.content.Context) {
        val dir = File(ctx.filesDir, "adb_keys4").apply { mkdirs() }
        val privFile = File(dir, "private.key")
        val pubFile = File(dir, "public.key")
        val kf = KeyFactory.getInstance("RSA")
        if (privFile.exists() && pubFile.exists()) {
            privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
            publicKey = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            privateKey = kp.private
            publicKey = kp.public as RSAPublicKey
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
        }
    }

    private fun doAuth(): Boolean {
        var signatureSent = false
        while (true) {
            val msg = read() ?: return false
            when (msg.command) {
                A_CNXN -> return true
                A_AUTH -> {
                    if (!signatureSent) {
                        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
                        val sig = cipher.doFinal(SHA1_PREFIX + msg.data)
                        send(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                        signatureSent = true
                    } else {
                        val blob = encodeAndroidPubKey(publicKey!!)
                        val keyStr = "$blob unkwn2@yandexhud".toByteArray() + byteArrayOf(0)
                        send(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, keyStr)
                    }
                }
                A_STLS -> {
                    Logger.e(TAG, "adbd wants TLS — use plain 'adb tcpip 5555'")
                    return false
                }
                else -> return false
            }
        }
    }

    fun exec(command: String): Result {
        if (output == null) return Result(false, error = "not connected")
        try {
            send(A_OPEN, 1, 0, "shell:$command\u0000".toByteArray())
            val baos = ByteArrayOutputStream()
            while (true) {
                val msg = read() ?: break
                when (msg.command) {
                    A_OKAY -> {}
                    A_WRTE -> {
                        baos.write(msg.data)
                        send(A_OKAY, msg.arg1, msg.arg0, ByteArray(0))
                    }
                    A_CLSE -> {
                        send(A_CLSE, msg.arg1, msg.arg0, ByteArray(0))
                        break
                    }
                }
            }
            return Result(true, output = baos.toString(Charsets.UTF_8.name()).trim())
        } catch (e: Exception) {
            Logger.e(TAG, "exec '$command': ${e.message}")
            return Result(false, error = e.message ?: "unknown")
        }
    }

    fun grantNotificationAccess(): Result =
        exec("cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener")

    fun grantAccessibility(): Result {
        val cur = exec("settings get secure enabled_accessibility_services").output
        val comp = "com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexA11yService"
        val merged = when {
            cur.isEmpty() || cur == "null" -> comp
            cur.contains(comp) -> cur
            else -> "$cur:$comp"
        }
        exec("settings put secure enabled_accessibility_services \"$merged\"")
        return exec("settings put secure accessibility_enabled 1")
    }

    fun grantMockLocation(): Result =
        exec("appops set com.unkwn2.yandexhud android:mock_location allow")

    fun grantAll(): List<Result> = listOf(
        grantNotificationAccess(),
        grantAccessibility(),
        grantMockLocation()
    )

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    // ---- android_pubkey (mincrypt) wire format ----
    private fun encodeAndroidPubKey(pub: RSAPublicKey): String {
        val words = 2048 / 32
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = (r32 - n.mod(r32).modInverse(r32)).toLong() and 0xFFFFFFFFL
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(words)
        bb.putInt(n0inv.toInt())
        putLeWords(bb, n, words)
        putLeWords(bb, rr, words)
        bb.putInt(pub.publicExponent.toInt())
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    private fun putLeWords(bb: ByteBuffer, value: BigInteger, words: Int) {
        val mask = BigInteger.ONE.shiftLeft(32) - BigInteger.ONE
        var v = value
        repeat(words) {
            bb.putInt((v and mask).toInt())
            v = v.shiftRight(32)
        }
    }

    // ---- framing ----
    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val out = output!!
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd); buf.putInt(arg0); buf.putInt(arg1)
        buf.putInt(data.size); buf.putInt(checksum(data)); buf.putInt(cmd xor -1)
        out.write(buf.array())
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xff)
        return sum
    }

    private data class AdbMsg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(): AdbMsg? {
        val hdr = ByteArray(24)
        var off = 0
        while (off < 24) { val n = input!!.read(hdr, off, 24 - off); if (n < 0) return null; off += n }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(); val a0 = bb.getInt(); val a1 = bb.getInt()
        val len = bb.getInt(); bb.getInt(); bb.getInt()
        val data = if (len > 0) { val b = ByteArray(len); var o = 0; while (o < len) { val n = input!!.read(b, o, len - o); if (n < 0) break; o += n }; b } else ByteArray(0)
        return AdbMsg(cmd, a0, a1, data)
    }
}
