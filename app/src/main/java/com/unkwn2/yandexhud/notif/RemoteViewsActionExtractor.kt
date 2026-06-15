package com.unkwn2.yandexhud.notif

import android.content.Context
import android.util.Log
import android.widget.RemoteViews

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
            val srcRes = try {
                ctx.createPackageContext(sourcePackage, Context.CONTEXT_IGNORE_SECURITY).resources
            } catch (_: Throwable) { null }

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

            return when (methodName) {
                "setImageResource", "setBackgroundResource" -> {
                    val resId = resourceIntField(fields, action) ?: 0
                    val op = if (methodName == "setImageResource") Op.IMAGE_RES else Op.BG_RES
                    val resName = resolveName(srcRes, resId)
                    RvAction(viewIdName, viewId, op, resName)
                }
                "setText" -> {
                    val text = charSequenceField(fields, action)?.toString() ?: ""
                    RvAction(viewIdName, viewId, Op.TEXT, text)
                }
                "setVisibility" -> {
                    val vis = resourceIntField(fields, action) ?: -1
                    RvAction(viewIdName, viewId, Op.VISIBILITY, vis.toString())
                }
                else -> classifyFallback(viewIdName, viewId, fields, action, methodName, srcRes)
            }
        } catch (_: Throwable) { return null }
    }

    private fun classifyFallback(
        viewIdName: String, viewId: Int,
        fields: List<java.lang.reflect.Field>, action: Any,
        methodName: String?, srcRes: android.content.res.Resources?
    ): RvAction? {
        val cs = try { charSequenceField(fields, action) } catch (_: Throwable) { null }
        if (cs != null) return RvAction(viewIdName, viewId, Op.TEXT, cs.toString())

        val resId = try { resourceIntField(fields, action) } catch (_: Throwable) { null }
        if (resId != null && resId != viewId && resId != 0) {
            val resName = try { resolveName(srcRes, resId) } catch (_: Throwable) { "" }
            if (resName.isNotEmpty()) {
                val op = if (methodName?.contains("background", true) == true) Op.BG_RES else Op.IMAGE_RES
                return RvAction(viewIdName, viewId, op, resName)
            }
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

    private fun resourceIntField(fields: List<java.lang.reflect.Field>, action: Any): Int? {
        val viewIdField = fields.firstOrNull { it.name == "viewId" }
        for (f in fields) {
            if (f === viewIdField) continue
            if (f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java) {
                return try { f.getInt(action) } catch (_: Throwable) { null }
            }
        }
        return null
    }

    private fun charSequenceField(fields: List<java.lang.reflect.Field>, action: Any): CharSequence? {
        for (f in fields) {
            if (CharSequence::class.java.isAssignableFrom(f.type)) {
                return try { f.get(action) as? CharSequence } catch (_: Throwable) { null }
            }
        }
        return null
    }

    private fun resolveName(res: android.content.res.Resources?, resId: Int): String {
        if (res == null || resId == 0) return ""
        return try { res.getResourceEntryName(resId).lowercase() } catch (_: Throwable) { "" }
    }
}
