package com.unkwn2.yandexhud.util

import android.content.Context
import android.content.res.Resources
import java.util.LinkedHashMap

object ResCache {
    private const val MAX_ENTRIES = 8
    private val cache = object : LinkedHashMap<String, Resources>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Resources>) = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(ctx: Context, pkg: String, flags: Int = Context.CONTEXT_IGNORE_SECURITY): Resources? {
        val key = "$pkg:$flags"
        cache[key]?.let { return it }
        val res = try {
            ctx.createPackageContext(pkg, flags).resources
        } catch (_: Throwable) { null }
        if (res != null) cache[key] = res
        return res
    }

    @Synchronized
    fun invalidate(pkg: String) {
        cache.keys.removeAll { it.startsWith("$pkg:") }
    }

    @Synchronized
    fun clear() { cache.clear() }
}
