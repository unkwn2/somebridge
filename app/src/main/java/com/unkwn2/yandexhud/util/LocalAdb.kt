package com.unkwn2.yandexhud.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object LocalAdb {
    private const val TAG = "LADB"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val CONNECT_TIMEOUT = 5000L

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x54585541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    private const val ADB_AUTH_TOKEN = 1
    private const val ADB_AUTH_SIGNATURE = 2
    private const val ADB_AUTH_RSAPUBLICKEY = 3

    private var privateKey: java.security.PrivateKey? = null
    private var certificate: X509Certificate? = null
    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    data class Result(val success: Boolean, val output: String = "", val error: String = "")

    fun init(ctx: android.content.Context): Boolean {
        try {
            val keyDir = File(ctx.filesDir, "adb_keys2")
            keyDir.mkdirs()
            val privFile = File(keyDir, "private.key")
            val certFile = File(keyDir, "cert.pem")
            val pubFile = File(keyDir, "public.key")

            if (privFile.exists() && certFile.exists()) {
                val kf = java.security.KeyFactory.getInstance("RSA")
                privateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privFile.readBytes()))
                val cf = CertificateFactory.getInstance("X.509")
                certificate = cf.generateCertificate(certFile.inputStream()) as X509Certificate
            } else {
                val kg = KeyPairGenerator.getInstance("RSA")
                kg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
                val kp = kg.generateKeyPair()
                privateKey = kp.private
                val holder = JcaX509v3CertificateBuilder(
                    X500Name("CN=YandexHUD"),
                    BigInteger(System.nanoTime().toString()),
                    Date(), Date(System.currentTimeMillis() + 365L * 86400000L),
                    X500Name("CN=YandexHUD"), kp.public
                ).build(JcaContentSignerBuilder("SHA256WithRSA").build(kp.private))
                val cf = CertificateFactory.getInstance("X.509")
                certificate = cf.generateCertificate(holder.encoded.inputStream()) as X509Certificate
                privFile.writeBytes(kp.private.encoded)
                pubFile.writeBytes(kp.public.encoded)
                certFile.outputStream().use { it.write(certificate!!.encoded) }
            }

            val ctx_ = SSLContext.getInstance("TLSv1.3")
            ctx_.init(
                arrayOf<KeyManager>(createKeyManager()),
                arrayOf<X509TrustManager>(createTrustManager()),
                SecureRandom()
            )
            socket = ctx_.socketFactory.createSocket(ADB_HOST, ADB_PORT) as SSLSocket
            socket!!.soTimeout = CONNECT_TIMEOUT.toInt()
            socket!!.startHandshake()
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())

            val hostBytes = "host::".toByteArray() + byteArrayOf(0)
            send(A_CNXN, 0x01000001, 4096, hostBytes)
            return doAuth()
        } catch (e: Exception) {
            Logger.e(TAG, "init: ${e.message}")
            disconnect()
            return false
        }
    }

    private fun doAuth(): Boolean {
        while (true) {
            val msg = read() ?: return false
            when (msg.command) {
                A_CNXN -> return true
                A_AUTH -> when (msg.arg0) {
                    ADB_AUTH_TOKEN -> {
                        val sig = Signature.getInstance("SHA1withRSA")
                        sig.initSign(privateKey)
                        sig.update(msg.data)
                        send(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig.sign())
                    }
                    ADB_AUTH_RSAPUBLICKEY -> {
                        val pubEnc = Base64.encodeToString(certificate!!.publicKey.encoded, Base64.NO_WRAP)
                        val keyStr = "$pubEnc unkwn2@yandexhud"
                        send(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, keyStr.toByteArray() + byteArrayOf(0))
                    }
                }
                else -> return false
            }
        }
    }

    fun exec(command: String): Result {
        if (socket?.isConnected != true) return Result(false, error = "not connected")
        try {
            val shellBytes = "shell:$command".toByteArray() + byteArrayOf(0)
            send(A_OPEN, 1, 0, shellBytes)
            val ok = read() ?: return Result(false, error = "no response")
            if (ok.command != A_OKAY) return Result(false, error = "expected OKAY got 0x${ok.command.toString(16)}")

            val baos = ByteArrayOutputStream()
            var closed = false
            while (!closed) {
                val msg = read() ?: break
                when (msg.command) {
                    A_WRTE -> {
                        baos.write(msg.data)
                        send(A_OKAY, msg.arg1, msg.arg0, ByteArray(0))
                    }
                    A_CLSE -> closed = true
                }
            }
            return Result(true, output = baos.toString(Charsets.UTF_8.name()).trim())
        } catch (e: Exception) {
            Logger.e(TAG, "exec '$command': ${e.message}")
            return Result(false, error = e.message ?: "unknown")
        }
    }

    fun grantNotificationAccess(): Result = exec("cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener")
    fun grantAccessibility(): Result = exec("settings put secure enabled_accessibility_services com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexA11yService")
    fun grantMockLocation(): Result = exec("appops set com.unkwn2.yandexhud android:mock_location allow")
    fun grantAll(): List<Result> = listOf(grantNotificationAccess(), grantAccessibility(), grantMockLocation())

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val crc = CRC32().also { it.update(data) }.value.toInt()
        val magic = cmd xor 0xFFFFFFFF.toInt()
        val buf = java.nio.ByteBuffer.allocate(24).also {
            it.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            it.putInt(cmd); it.putInt(arg0); it.putInt(arg1)
            it.putInt(data.size); it.putInt(crc); it.putInt(magic)
        }.array()
        output!!.write(buf)
        if (data.isNotEmpty()) output!!.write(data)
        output!!.flush()
    }

    private data class AdbMsg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(): AdbMsg? {
        val hdr = ByteArray(24)
        var off = 0
        while (off < 24) { val n = input!!.read(hdr, off, 24 - off); if (n < 0) return null; off += n }
        val bb = java.nio.ByteBuffer.wrap(hdr).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(); val a0 = bb.getInt(); val a1 = bb.getInt()
        val len = bb.getInt(); bb.getInt(); bb.getInt()
        val data = if (len > 0) { val b = ByteArray(len); var o = 0; while (o < len) { val n = input!!.read(b, o, len - o); if (n < 0) break; o += n }; b } else ByteArray(0)
        return AdbMsg(cmd, a0, a1, data)
    }

    private fun createKeyManager(): KeyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(p0: String?, p1: Array<out java.security.Principal>?) = null
        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? {
            return if (keyTypes?.any { it == "RSA" } == true) "adb" else null
        }
        override fun getServerAliases(p0: String?, p1: Array<out java.security.Principal>?) = null
        override fun chooseServerAlias(p0: String?, p1: Array<out java.security.Principal>?, p2: java.net.Socket?) = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            return if (alias == "adb") arrayOf(certificate!!) else null
        }
        override fun getPrivateKey(alias: String?): java.security.PrivateKey? {
            return if (alias == "adb") privateKey else null
        }
    }

    private fun createTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
        override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
