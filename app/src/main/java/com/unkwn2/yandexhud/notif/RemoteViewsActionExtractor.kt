package com.unkwn2.yandexhud.notif

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.unkwn2.yandexhud.util.ResCache

object RemoteViewsActionExtractor {
    private const val TAG = "RvActionExtract"

    enum class Op { TEXT, IMAGE_RES, BG_RES, VISIBILITY, OTHER }

    data class RvAction(
        val viewIdName: String,
        val viewId: Int,
        val op: Op,
        val value: String,
    )

    fun extractActions(ctx: Context, rv: RemoteViews, sourcePackage: String): List<RvAction> {
        return try {
            val srcRes = ResCache.get(ctx, sourcePackage, Context.CONTEXT_IGNORE_SECURITY)

            val actionsField = RemoteViews::class.java.getDeclaredField("mActions").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val actions = actionsField.get(rv) as? ArrayList<Any> ?: return emptyList()

            val out = ArrayList<RvAction>(actions.size)
            for (action in actions) {
                val a = readAction(action, srcRes) ?: continue
                out.add(a)
            }
            out
        } catch (t: Throwable) {
            Log.d(TAG, "mActions reflection unavailable: ${t.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun readAction(action: Any, srcRes: android.content.res.Resources?): RvAction? {
        try {
            val fields = collectFields(action.javaClass)
            val viewId = intFieldValue(fields, "viewId", action) ?: return null
            val methodName = stringFieldValue(fields, "methodName", action)
            val viewIdName = resolveName(srcRes, viewId)
            val value = valueObject(fields, action)

            return when (methodName) {
                "setImageResource", "setBackgroundResource" -> {
                    val resId = value as? Int ?: return null
                    val op = if (methodName == "setImageResource") Op.IMAGE_RES else Op.BG_RES
                    RvAction(viewIdName, viewId, op, resolveName(srcRes, resId))
                }
                "setText" -> RvAction(viewIdName, viewId, Op.TEXT, (value as? CharSequence)?.toString() ?: "")
                "setVisibility" -> RvAction(viewIdName, viewId, Op.VISIBILITY, ((value as? Int) ?: -1).toString())
                else -> when (value) {
                    is CharSequence -> RvAction(viewIdName, viewId, Op.TEXT, value.toString())
                    is Int -> {
                        val nm = resolveName(srcRes, value)
                        if (nm.isNotEmpty())
                            RvAction(viewIdName, viewId,
                                if (methodName?.contains("background", true) == true) Op.BG_RES else Op.IMAGE_RES, nm)
                        else null
                    }
                    else -> null
                }
            }
        } catch (_: Throwable) { return null }
    }

    private fun valueObject(fields: List<java.lang.reflect.Field>, action: Any): Any? {
        fields.firstOrNull { it.name == "value" }?.let {
            return try { it.get(action) } catch (_: Throwable) { null }
        }
        for (f in fields) {
            if (f.name == "viewId" || f.name == "methodName" || f.type.isPrimitive) continue
            val v = try { f.get(action) } catch (_: Throwable) { null }
            if (v is CharSequence || v is Int) return v
        }
        return null
    }

    private fun collectFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val all = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = cls
        while (c != null) {
            for (f in c.declaredFields) {
                f.isAccessible = true
                all += f
            }
            c = c.superclass
        }
        return all
    }

    private fun intFieldValue(fields: List<java.lang.reflect.Field>, name: String, action: Any): Int? {
        val f = fields.firstOrNull { it.name == name && (it.type == Int::class.javaPrimitiveType || it.type == Integer::class.java) } ?: return null
        return try { f.getInt(action) } catch (_: Throwable) { null }
    }

    private fun stringFieldValue(fields: List<java.lang.reflect.Field>, name: String, action: Any): String? {
        val f = fields.firstOrNull { it.name == name && it.type == String::class.java } ?: return null
        return try { f.get(action) as? String } catch (_: Throwable) { null }
    }

    private fun resolveName(res: android.content.res.Resources?, resId: Int): String {
        if (res == null || resId == 0) return ""
        return try { res.getResourceEntryName(resId).lowercase() } catch (_: Throwable) { "" }
    }
}
