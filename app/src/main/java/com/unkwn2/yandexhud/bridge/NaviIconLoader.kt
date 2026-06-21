package com.unkwn2.yandexhud.bridge

import android.content.Context
import com.unkwn2.yandexhud.util.Logger

object NaviIconLoader {
    private const val TAG = "ICONLOAD"
    @Volatile private var appContext: Context? = null

    fun init(ctx: Context) { appContext = ctx }

    fun loadLarge(gaodeCode: Int): ByteArray? {
        val ctx = appContext ?: return null
        val name = "navi/0x${gaodeCode.toString(16)}.png"
        return try {
            ctx.assets.open(name).readBytes().also {
                Logger.i(TAG, "loaded $name (${it.size}B)")
            }
        } catch (_: Exception) { null }
    }

    fun loadSmall(gaodeCode: Int): ByteArray? {
        val name = "navi/0x${gaodeCode.toString(16)}_s.png"
        val ctx = appContext ?: return null
        return try {
            ctx.assets.open(name).readBytes().also {
                Logger.i(TAG, "loaded $name (${it.size}B)")
            }
        } catch (_: Exception) { null }
    }
}
