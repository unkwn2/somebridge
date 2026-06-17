package com.unkwn2.yandexhud.util

import android.content.Context
import android.content.SharedPreferences

object LicenseManager {
    private const val PREFS_NAME = "yandexhud_license"
    private const val KEY_LICENSE = "license_data"
    private const val SECRET_KEY = "сделано с любовью и вайбкодингом + бессонные ночи. разработчик @rbgboost"

    fun isLicenseValid(ctx: Context): Boolean {
        val stored = getPrefs(ctx).getString(KEY_LICENSE, null) ?: return false
        return stored == SECRET_KEY
    }

    fun saveLicense(ctx: Context, licenseStr: String): Boolean {
        if (licenseStr != SECRET_KEY) return false
        getPrefs(ctx).edit().putString(KEY_LICENSE, licenseStr).apply()
        return true
    }

    fun clearLicense(ctx: Context) {
        getPrefs(ctx).edit().clear().apply()
    }

    private fun getPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
