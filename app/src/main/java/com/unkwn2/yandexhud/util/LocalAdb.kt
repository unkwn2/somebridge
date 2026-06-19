package com.unkwn2.yandexhud.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey

object LocalAdb {
    private const val TAG = "LADB"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43
    private const val A_STLS = 0x534c5453

    private const val ADB_AUTH_TOKEN = 1
    private const val ADB_AUTH_SIGNATURE = 2
    private const val ADB_AUTH_RSAPUBLICKEY = 3

    private const val VERSION = 0x01000000
    private const val MAXDATA = 256 * 1024

    private val lock = Any()
    @Volatile private var privateKey: java.security.PrivateKey? = null
    @Volatile private var publicKey: RSAPublicKey? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null

    data class Result(val success: Boolean, val output: String = "", val error: String = "")

    fun init(ctx: android.content.Context): Boolean {
        synchronized(lock) {
            if (socket != null && socket?.isConnected == true) {
                return true
            }
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
                val authOk = doAuth()
                if (authOk) {
                    persistAdbKey(ctx)
                }
                return authOk
            } catch (e: Exception) {
                Logger.e(TAG, "init: ${e.message}")
                disconnect()
                return false
            }
        }
    }

    private fun persistAdbKey(ctx: android.content.Context) {
        try {
            val pubFile = java.io.File(ctx.filesDir, "adb_key.pub.der")
            if (!pubFile.exists()) return
            val kf = KeyFactory.getInstance("RSA")
            val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
            val blob = encodeAndroidPubKey(pub)
            val keyLine = "$blob unkwn2@yandexhud"
            val keysFile = exec("cat /data/misc/adb/adb_keys").output
            if (keyLine in keysFile) return
            exec("echo '$keyLine' >> /data/misc/adb/adb_keys")
            Logger.i(TAG, "persisted adb key to /data/misc/adb/adb_keys")
        } catch (e: Exception) {
            Logger.w(TAG, "persistAdbKey: ${e.message}")
        }
    }

    private fun loadOrCreateKeys(ctx: android.content.Context) {
        try {
            val keyFile = java.io.File(ctx.filesDir, "adb_key.der")
            if (keyFile.exists()) {
                val encoded = keyFile.readBytes()
                val kf = KeyFactory.getInstance("RSA")
                privateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(encoded))
                val pubFile = java.io.File(ctx.filesDir, "adb_key.pub.der")
                if (pubFile.exists()) {
                    val pubEnc = pubFile.readBytes()
                    publicKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(pubEnc)) as RSAPublicKey
                }
            } else {
                val kpg = KeyPairGenerator.getInstance("RSA")
                kpg.initialize(2048, SecureRandom())
                val kp = kpg.generateKeyPair()
                privateKey = kp.private
                publicKey = kp.public as RSAPublicKey
                keyFile.writeBytes(kp.private.encoded)
                java.io.File(ctx.filesDir, "adb_key.pub.der").writeBytes(kp.public.encoded)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "keygen: ${e.message}")
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            privateKey = kp.private
            publicKey = kp.public as RSAPublicKey
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
                        val sig = try {
                            val s = Signature.getInstance("SHA256withRSA")
                            s.initSign(privateKey)
                            s.update(msg.data)
                            s.sign()
                        } catch (_: Exception) {
                            // fallback SHA-1 for older adbd
                            val s = Signature.getInstance("SHA1withRSA")
                            s.initSign(privateKey)
                            s.update(msg.data)
                            s.sign()
                        }
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
        synchronized(lock) {
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

    fun grantNaviSettings(): Result =
        exec("settings put global navi_screen_status 3")

    fun grantBackgroundRun(): Result =
        exec("appops set com.unkwn2.yandexhud RUN_IN_BACKGROUND allow")

    fun grantBatteryWhitelist(): Result =
        exec("dumpsys deviceidle whitelist +com.unkwn2.yandexhud")

    fun grantAll(): List<Result> = listOf(
        grantNotificationAccess(),
        grantAccessibility(),
        grantMockLocation(),
        grantNaviSettings(),
        grantBackgroundRun(),
        grantBatteryWhitelist()
    )

    fun dumpLogcat(): Result {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return exec("logcat -d -s YandexHUD YA11Y YandexNotif LOOP FGS SIPBR UI TEST !YNDX > /sdcard/hud_$ts.log")
    }

    fun disconnect() {
        synchronized(lock) {
            try { socket?.close() } catch (_: Exception) {}
            socket = null; input = null; output = null
        }
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
