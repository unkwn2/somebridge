package com.unkwn2.yandexhud.util

import android.content.Context
import android.content.res.Resources
import java.util.LinkedHashMap

object ResCache {
    private const val MAX_ENTRIES = 4
    private val cache = object : LinkedHashMap<String, Resources?>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Resources?>) = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(ctx: Context, pkg: String, flags: Int = 0): Resources? {
        cache[pkg]?.let { return it }
        val res = try {
            ctx.createPackageContext(pkg, flags).resources
        } catch (_: Throwable) { null }
        cache[pkg] = res
        return res
    }

    @Synchronized
    fun invalidate(pkg: String) { cache.remove(pkg) }

    @Synchronized
    fun clear() { cache.clear() }
}
