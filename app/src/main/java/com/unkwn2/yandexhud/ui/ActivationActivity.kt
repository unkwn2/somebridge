package com.unkwn2.yandexhud.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.unkwn2.yandexhud.R
import com.unkwn2.yandexhud.util.LicenseManager

class ActivationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if license is already valid
        if (LicenseManager.isLicenseValid(this)) {
            startMain()
            return
        }

        setContentView(R.layout.activity_activation)

        val deviceId = LicenseManager.getDeviceId(this)
        findViewById<TextView>(R.id.deviceIdText).text = deviceId

        findViewById<TextView>(R.id.copyDeviceId).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("device_id", deviceId))
            Toast.makeText(this, "Device ID copied", Toast.LENGTH_SHORT).show()
        }

        val licenseInput = findViewById<EditText>(R.id.licenseInput)
        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            val licenseStr = licenseInput.text.toString().trim()
            if (licenseStr.isEmpty()) {
                Toast.makeText(this, "Enter license key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = LicenseManager.saveLicense(this, licenseStr)
            if (ok) {
                Toast.makeText(this, "License activated!", Toast.LENGTH_SHORT).show()
                startMain()
            } else {
                Toast.makeText(this, "Invalid license key", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}