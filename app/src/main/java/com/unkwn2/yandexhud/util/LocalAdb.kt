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
    @Volatile private var permsSatisfied = false
    @Volatile private var localIdSeq = 1

    data class Result(val success: Boolean, val output: String = "", val error: String = "")

    /** Переиспользует живой сокет; полный init() — только если коннекта нет. */
    fun ensureConnected(ctx: android.content.Context): Boolean {
        synchronized(lock) {
            val s = socket
            if (s != null && s.isConnected && !s.isClosed && output != null) {
                val r = exec("echo __ping__")
                if (r.success && r.output.contains("__ping__")) return true
                disconnect()
            }
            return init(ctx)
        }
    }

    fun init(ctx: android.content.Context): Boolean {
        synchronized(lock) {
            disconnect()
            val delays = longArrayOf(1000, 3000, 5000)
            var lastError: String? = null
            for (attempt in 1..3) {
                try {
                    loadOrCreateKeys(ctx)
                    val s = Socket()
                    s.connect(InetSocketAddress(ADB_HOST, ADB_PORT), 5000)
                    s.soTimeout = 15000
                    socket = s
                    input = DataInputStream(s.getInputStream())
                    output = DataOutputStream(s.getOutputStream())

                    val hostBytes = "host::\u0000".toByteArray()
                    send(A_CNXN, VERSION, MAXDATA, hostBytes)
                    if (doAuth()) {
                        s.soTimeout = 60000
                        exec("setprop service.adb.tcp.port 5555")
                        Logger.i(TAG, "init ok (attempt $attempt)")
                        return true
                    }
                    disconnect()
                } catch (e: Exception) {
                    lastError = e.message
                    Logger.w(TAG, "init attempt $attempt: ${e.message}")
                    disconnect()
                    if (attempt < 3) {
                        try { Thread.sleep(delays[attempt - 1]) } catch (_: InterruptedException) { break }
                    }
                }
            }
            Logger.e(TAG, "init failed: $lastError")
            return false
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
        var pubkeySent = false
        while (true) {
            val msg = read() ?: return false
            when (msg.command) {
                A_CNXN -> return true
                A_AUTH -> {
                    if (!signatureSent) {
                        val sig = signToken(msg.data)
                        send(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                        signatureSent = true
                    } else if (!pubkeySent) {
                        val blob = encodeAndroidPubKey(publicKey!!)
                        val keyStr = "$blob unkwn2@yandexhud".toByteArray() + byteArrayOf(0)
                        send(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, keyStr)
                        pubkeySent = true
                    } else {
                        Logger.w(TAG, "doAuth: extra token after pubkey, re-signing")
                        val sig = signToken(msg.data)
                        send(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
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

    private fun signToken(token: ByteArray): ByteArray = try {
        val s = Signature.getInstance("SHA256withRSA")
        s.initSign(privateKey)
        s.update(token)
        s.sign()
    } catch (_: Exception) {
        val s = Signature.getInstance("SHA1withRSA")
        s.initSign(privateKey)
        s.update(token)
        s.sign()
    }

    fun exec(command: String): Result {
        synchronized(lock) {
            if (output == null) return Result(false, error = "not connected")
            try {
                val localId = localIdSeq++
                send(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())
                val baos = ByteArrayOutputStream()
                while (true) {
                    val msg = read() ?: break
                    if (msg.arg1 != localId) {
                        // Хвост прошлого стрима — ACK и пропуск
                        if (msg.command == A_WRTE) send(A_OKAY, msg.arg1, msg.arg0, ByteArray(0))
                        continue
                    }
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
        exec("cmd notification allow_listener $COMPONENT_NOTIF")

    /** Toggle (disallow → allow) чтобы система перeregистрировала listener. */
    fun toggleNotifListener(): Result {
        exec("cmd notification disallow_listener $COMPONENT_NOTIF")
        try { Thread.sleep(500) } catch (_: InterruptedException) {}
        return exec("cmd notification allow_listener $COMPONENT_NOTIF")
    }

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

    private val COMPONENT_NOTIF = "com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener"
    private val COMPONENT_A11Y = "com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexA11yService"

    fun checkNotificationAccess(): Boolean {
        val r = exec("settings get secure enabled_notification_listeners")
        return r.success && r.output.contains(COMPONENT_NOTIF)
    }

    fun checkAccessibility(): Boolean {
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        val r = exec("settings get secure enabled_accessibility_services")
        return r.success && r.output.contains(COMPONENT_A11Y)
    }

    fun checkMockLocation(): Boolean {
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        val r = exec("appops get com.unkwn2.yandexhud android:mock_location")
        return r.success && !r.output.contains("deny")
    }

    fun checkNaviSettings(): Boolean {
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        val r = exec("settings get global navi_screen_status")
        return r.success && r.output.trim() == "3"
    }

    fun checkBackgroundRun(): Boolean {
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        val r = exec("appops get com.unkwn2.yandexhud RUN_IN_BACKGROUND")
        return r.success && r.output.contains("allow")
    }

    fun checkBatteryWhitelist(): Boolean {
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        val r = exec("dumpsys deviceidle whitelist")
        return r.success && r.output.contains("com.unkwn2.yandexhud")
    }

    /** Идемпотентно: чек → грант только недостающего. Гранты в ОДНОМ shell — без перемешивания выходов. */
    fun ensurePermissions(ctx: android.content.Context, force: Boolean = false): Boolean {
        synchronized(lock) {
            if (permsSatisfied && !force) return true
            if (!ensureConnected(ctx)) { Logger.w(TAG, "ensurePermissions: ADB connect failed"); return false }

            // 1. Проверяем что нужно
            val needNotif = !checkNotificationAccess()
            val needA11y = !checkAccessibility()
            val needMock = !checkMockLocation()
            val needNavi = !checkNaviSettings()
            val needBg = !checkBackgroundRun()
            val needBatt = !checkBatteryWhitelist()

            if (!needNotif && !needA11y && !needMock && !needNavi && !needBg && !needBatt) {
                permsSatisfied = true
                Logger.i(TAG, "ensurePermissions: all already granted")
                return true
            }

            // 2. Собираем ВСЕ гранты в одну shell-команду
            val cmds = mutableListOf<String>()
            if (needNotif) cmds.add("cmd notification allow_listener $COMPONENT_NOTIF")
            if (needA11y) {
                val cur = exec("settings get secure enabled_accessibility_services").output
                val merged = when {
                    cur.isEmpty() || cur == "null" -> COMPONENT_A11Y
                    cur.contains(COMPONENT_A11Y) -> cur
                    else -> "$cur:$COMPONENT_A11Y"
                }
                cmds.add("settings put secure enabled_accessibility_services \"$merged\"")
                cmds.add("settings put secure accessibility_enabled 1")
            }
            if (needMock) cmds.add("appops set com.unkwn2.yandexhud android:mock_location allow")
            if (needNavi) cmds.add("settings put global navi_screen_status 3")
            if (needBg) cmds.add("appops set com.unkwn2.yandexhud RUN_IN_BACKGROUND allow")
            if (needBatt) cmds.add("dumpsys deviceidle whitelist +com.unkwn2.yandexhud")

            val batchCmd = cmds.joinToString(" ; ")
            Logger.i(TAG, "ensurePermissions granting: ${cmds.size} cmds in one shell")
            val r = exec(batchCmd)
            Logger.i(TAG, "  batch result: success=${r.success} out='${r.output.take(120)}'")

            // 3. Ждём системе устаканиться
            try { Thread.sleep(500L) } catch (_: InterruptedException) {}

            // 3b. Toggle notif listener чтобы система перeregистрировала его
            if (checkNotificationAccess()) {
                Logger.i(TAG, "ensurePermissions: toggling notif listener for rebind")
                toggleNotifListener()
                try { Thread.sleep(300L) } catch (_: InterruptedException) {}
            }

            // 4. Перепроверяем
            permsSatisfied = checkNotificationAccess() && checkAccessibility() &&
                checkMockLocation() && checkNaviSettings() && checkBackgroundRun() && checkBatteryWhitelist()
            val results = "NOTIF=${checkNotificationAccess()} A11Y=${checkAccessibility()} " +
                "MOCK=${checkMockLocation()} NAVI=${checkNaviSettings()} BG=${checkBackgroundRun()} BATT=${checkBatteryWhitelist()}"
            Logger.i(TAG, "ensurePermissions final allOk=$permsSatisfied $results")
            return permsSatisfied
        }
    }

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
