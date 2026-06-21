package com.unkwn2.yandexhud.bridge

import android.content.Context
import com.unkwn2.yandexhud.util.Logger

object NaviIconLoader {
    private const val TAG = "ICONLOAD"
    @Volatile private var appContext: Context? = null
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
    private val miss = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    fun init(ctx: Context) { appContext = ctx }

    fun loadLarge(code: Int): ByteArray? {
        cache[code]?.let { return it }
        if (code in miss) return null
        val ctx = appContext ?: return null
        val name = "navi/0x${code.toString(16)}.png"
        val png = try {
            ctx.assets.open(name).readBytes().also {
                Logger.i(TAG, "loaded $name (${it.size}B)")
            }
        } catch (_: Exception) { null }
        if (png != null) cache[code] = png else miss += code
        return png
    }

    fun loadSmall(code: Int): ByteArray? = loadLarge(code)
}
