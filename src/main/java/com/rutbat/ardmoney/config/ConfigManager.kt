package com.rutbat.ardmoney.config

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.InputStream

object ConfigManager {
    private lateinit var config: JSONObject
    private const val TAG = "ConfigManager"

    fun init(context: Context) {
        try {
            val inputStream: InputStream = context.assets.open("config.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            config = JSONObject(jsonString)
            if (!validateConfig(config)) {
                Log.e(TAG, "Недопустимый формат config.json, используются значения по умолчанию")
                config = createDefaultConfig()
                showConfigErrorToast(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось загрузить config.json: ${e.message}", e)
            config = createDefaultConfig()
            showConfigErrorToast(context)
        }
    }

    private fun createDefaultConfig(): JSONObject {
        return JSONObject(
            """{
                "webview_url": "https://ardmoney.ru",
                "fcm_enabled": true,
                "splash_screen_enabled": true,
                "check_version_url": "https://ardmoney.ru/api/check_version.php"
            }"""
        )
    }

    private fun validateConfig(json: JSONObject): Boolean {
        return json.has("webview_url") &&
                json.has("fcm_enabled") &&
                json.has("splash_screen_enabled") &&
                json.has("check_version_url")
    }

    private fun showConfigErrorToast(context: Context) {
        Toast.makeText(context, "Ошибка загрузки конфигурации, применены значения по умолчанию", Toast.LENGTH_LONG).show()
    }

    fun getConfig(): JSONObject {
        if (!::config.isInitialized) {
            throw IllegalStateException("ConfigManager не инициализирован. Сначала вызовите init().")
        }
        return config
    }

    // Метод, который всегда возвращает String, обрабатывая null внутри
    fun getString(key: String, default: String): String {
        return try {
            getConfig().getString(key) ?: default
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка получения строки для ключа $key, используется значение по умолчанию: ${e.message}")
            default
        }
    }

    // Метод, который всегда возвращает Boolean, обрабатывая null внутри
    fun getBoolean(key: String, default: Boolean): Boolean {
        return try {
            getConfig().getBoolean(key)
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка получения boolean для ключа $key, используется значение по умолчанию: ${e.message}")
            default
        }
    }
}