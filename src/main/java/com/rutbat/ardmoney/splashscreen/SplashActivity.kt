package com.rutbat.ardmoney.splashscreen

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.rutbat.ardmoney.R
import com.rutbat.ardmoney.config.AppConfig
import com.rutbat.ardmoney.core.MainActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AppConfig.SPLASH_SCREEN_ENABLED) {
            startMainActivity()
            return
        }

        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            startMainActivity()
        }, 300)
    }

    private fun startMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e("SplashActivity", "Failed to start MainActivity", e)
            finish()
        }
    }
}