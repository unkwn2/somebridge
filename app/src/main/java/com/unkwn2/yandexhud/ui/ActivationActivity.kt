package com.unkwn2.yandexhud.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.unkwn2.yandexhud.R
import com.unkwn2.yandexhud.util.LicenseManager

class ActivationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (LicenseManager.isLicenseValid(this)) {
            startMain()
            return
        }

        setContentView(R.layout.activity_activation)

        val licenseInput = findViewById<EditText>(R.id.licenseInput)
        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            val key = licenseInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter key / Введите ключ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = LicenseManager.saveLicense(this, key)
            if (ok) {
                Toast.makeText(this, "Activated! / Активировано!", Toast.LENGTH_SHORT).show()
                startMain()
            } else {
                Toast.makeText(this, "Invalid key / Неверный ключ", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
