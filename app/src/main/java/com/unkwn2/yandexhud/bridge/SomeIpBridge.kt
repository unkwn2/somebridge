package com.unkwn2.yandexhud.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.unkwn2.yandexhud.sniff.ProtobufParser
import com.unkwn2.yandexhud.util.Logger

class SomeIpBridge(private val ctx: Context) {

    companion object {
        private const val TAG = "SIPBR"
        private const val PKG = "com.ts.car.someip.service"
        private const val SERVER_ACTION = "com.ts.car.someip.SomeIpServerService"
        private const val CLIENT_ACTION = "com.ts.car.someip.SomeIpClientService"
        private const val DESC = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CLIENT_DESC = "ts.car.someip.sdk.ISomeIpClientInterface"
        private const val CB_DESC = "ts.car.someip.sdk.ISomeIpCallback"

        private const val TX_REGISTER_CB = IBinder.FIRST_CALL_TRANSACTION
        private const val TX_UNREGISTER_CB = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TX_IS_READY = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TX_START_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TX_STOP_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TX_FIRE_EVENT = IBinder.FIRST_CALL_TRANSACTION + 5

        const val TOPIC_NAVI = 0x4010a00018001L
        const val TOPIC_NAVMAP = 0x4010a00018002L
        const val SERVICE_ID_NAVI = 0xB010A00010000L
    }

    @Volatile private var serverBinder: IBinder? = null
    @Volatile private var clientBinder: IBinder? = null
    @Volatile private var serverConnected = false
    @Volatile private var clientConnected = false

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(CB_DESC)
                    return true
                }
                1 -> {
                    data.enforceInterface(CB_DESC)
                    if (data.readInt() != 0) {
                        val topic = data.readLong()
                        val ts = data.readLong()
                        val len = data.readInt()
                        if (len in 0..65536) {
                            val pl = ByteArray(len)
                            data.readByteArray(pl)
                            Logger.i(TAG, "CB event topic=0x${topic.toString(16)} ts=$ts len=$len")
                            Logger.i(TAG, "CB payload ${pl.size}B: ${ProtobufParser.format(pl)}")
                        }
                    }
                    reply?.writeNoException()
                    reply?.writeInt(0)
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private val serverConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            serverBinder = service
            serverConnected = true
            Logger.i(TAG, "server connected $name")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serverBinder = null; serverConnected = false
            Logger.w(TAG, "server disconnected $name")
        }
    }

    private val clientConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            clientBinder = service
            clientConnected = true
            Logger.i(TAG, "client connected $name")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            clientBinder = null; clientConnected = false
            Logger.w(TAG, "client disconnected $name")
        }
    }

    fun bind(onDone: (Boolean) -> Unit) {
        Thread {
            val maxRetries = 3
            val backoffMs = longArrayOf(1000, 3000, 7000)
            for (attempt in 0 until maxRetries) {
                if (attempt > 0) {
                    Logger.i(TAG, "bind retry #$attempt in ${backoffMs[attempt]}ms")
                    try { Thread.sleep(backoffMs[attempt]) } catch (_: InterruptedException) { break }
                }
                try { if (serverConnected) ctx.unbindService(serverConn) } catch (_: Throwable) {}
                serverBinder = null; serverConnected = false

                val intent = Intent(SERVER_ACTION).setPackage(PKG)
                val rc = ctx.bindService(intent, serverConn, Context.BIND_AUTO_CREATE)
                Logger.i(TAG, "bind server rc=$rc attempt=#$attempt")
                if (!rc) continue

                var tries = 0
                while (serverBinder == null && tries++ < 75) Thread.sleep(200)
                if (serverBinder != null) {
                    registerCallback(serverBinder!!)
                    onDone(true)
                    return@Thread
                }
                Logger.w(TAG, "server bind timeout attempt=#$attempt")
            }
            Logger.e(TAG, "server bind failed after $maxRetries attempts")
            onDone(false)
        }.start()
    }

    fun unbind() {
        try { if (serverConnected) ctx.unbindService(serverConn) } catch (_: Throwable) {}
        try { if (clientConnected) ctx.unbindService(clientConn) } catch (_: Throwable) {}
        serverBinder = null; serverConnected = false
        clientBinder = null; clientConnected = false
        Logger.i(TAG, "unbound")
    }

    fun isReady(): Boolean {
        val b = serverBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            b.transact(TX_IS_READY, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } catch (_: Throwable) { return false }
        finally { data.recycle(); reply.recycle() }
    }

    fun startService(topic: Long): Int {
        val b = serverBinder ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            data.writeLong(topic)
            b.transact(TX_START_SERVICE, data, reply, 0)
            reply.readException()
            val rc = reply.readInt()
            Logger.i(TAG, "startService(0x${topic.toString(16)}) rc=$rc")
            return rc
        } catch (t: Throwable) {
            Logger.e(TAG, "startService exception: ${t.message}")
            return -2
        } finally { data.recycle(); reply.recycle() }
    }

    fun stopService(topic: Long): Int {
        val b = serverBinder ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            data.writeLong(topic)
            b.transact(TX_STOP_SERVICE, data, reply, 0)
            reply.readException()
            val rc = reply.readInt()
            Logger.i(TAG, "stopService(0x${topic.toString(16)}) rc=$rc")
            return rc
        } catch (_: Throwable) { return -2 }
        finally { data.recycle(); reply.recycle() }
    }

    fun fireEvent(topic: Long, payload: ByteArray): Int {
        val b = serverBinder ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            data.writeInt(1)
            data.writeLong(topic)
            data.writeLong(0L)
            data.writeInt(payload.size)
            data.writeByteArray(payload)
            b.transact(TX_FIRE_EVENT, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } catch (t: Throwable) {
            Logger.e(TAG, "fireEvent exception: ${t.message}")
            return -2
        } finally { data.recycle(); reply.recycle() }
    }

    private fun registerCallback(binder: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC)
            data.writeStrongBinder(callback)
            binder.transact(TX_REGISTER_CB, data, reply, 0)
            reply.readException()
            val rc = reply.readInt()
            Logger.i(TAG, "registerCallback rc=$rc")
            return rc
        } catch (t: Throwable) {
            Logger.e(TAG, "registerCallback exception: ${t.message}")
            return -999
        } finally { data.recycle(); reply.recycle() }
    }

    fun snifferStart() {
        Thread {
            val intent = Intent(CLIENT_ACTION).setPackage(PKG)
            val rc = ctx.bindService(intent, clientConn, Context.BIND_AUTO_CREATE)
            if (!rc) { Logger.e(TAG, "sniffer bind failed"); return@Thread }
            var tries = 0
            while (clientBinder == null && tries++ < 30) Thread.sleep(200)
            val cb = clientBinder ?: run { Logger.e(TAG, "sniffer timeout"); return@Thread }

            // Step 1: registerCallback (tx=1)
            Logger.i(TAG, "sniffer: registerCallback...")
            run {
                val d = Parcel.obtain(); val r = Parcel.obtain()
                try {
                    d.writeInterfaceToken(CLIENT_DESC)
                    d.writeStrongBinder(callback)
                    cb.transact(1, d, r, 0)
                    r.readException()
                    Logger.i(TAG, "sniffer: registerCallback ok")
                } catch (t: Throwable) { Logger.e(TAG, "sniffer regCb: ${t.message}") }
                finally { r.recycle(); d.recycle() }
            }

            Thread.sleep(200)

            // Step 2: startClients (tx=6) — без аргументов
            Logger.i(TAG, "sniffer: startClients...")
            run {
                val d = Parcel.obtain(); val r = Parcel.obtain()
                try {
                    d.writeInterfaceToken(CLIENT_DESC)
                    cb.transact(6, d, r, 0)
                    r.readException()
                    val rc2 = r.readInt()
                    Logger.i(TAG, "sniffer: startClients rc=$rc2")
                } catch (t: Throwable) { Logger.e(TAG, "sniffer startClients: ${t.message}") }
                finally { r.recycle(); d.recycle() }
            }

            Thread.sleep(200)

            // Step 3: subscribe (tx=8) на три топика
            val topics = listOf(0x4010a00018001L, 0x4010a00018002L, 0x4010a00018003L)
            for (topic in topics) {
                val d = Parcel.obtain(); val r = Parcel.obtain()
                try {
                    d.writeInterfaceToken(CLIENT_DESC)
                    d.writeLong(topic)
                    cb.transact(8, d, r, 0)
                    r.readException()
                    val subRc = r.readInt()
                    Logger.i(TAG, "sniffer subscribe tx=8(0x${topic.toString(16)}) rc=$subRc")
                } catch (t: Throwable) {
                    Logger.e(TAG, "sniffer subscribe tx=8(0x${topic.toString(16)}) err: ${t.message}")
                } finally { r.recycle(); d.recycle() }
                Thread.sleep(100)
            }
            Logger.i(TAG, "sniffer active — waiting for events")
        }.start()
    }

    fun snifferStop() {
        try { if (clientConnected) ctx.unbindService(clientConn) } catch (_: Throwable) {}
        clientBinder = null; clientConnected = false
        Logger.i(TAG, "sniffer stopped")
    }
}
