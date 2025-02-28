package com.rutbat.ardmoney.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.rutbat.ardmoney.R

class NoInternetActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "NoInternetActivity"
    private val checkInternetRunnable = object : Runnable {
        override fun run() {
            if (isNetworkAvailable()) {
                val targetUrl = intent.getStringExtra("targetUrl")
                val returnIntent = Intent(this@NoInternetActivity, MainActivity::class.java).apply {
                    putExtra("targetUrl", targetUrl)
                    putExtra("checkUpdate", true) // Сигнал для проверки обновлений
                }
                startActivity(returnIntent)
                finish()
            } else {
                handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_no_internet)

        videoView = findViewById(R.id.videoView)

        // Установка пути к видео из папки raw
        val videoPath = "android.resource://${packageName}/${R.raw.cat}"
        videoView.setVideoPath(videoPath)

        // Растягиваем видео на весь экран
        videoView.setOnPreparedListener { mediaPlayer ->
            val videoWidth = mediaPlayer.videoWidth
            val videoHeight = mediaPlayer.videoHeight
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            val scaleX = screenWidth.toFloat() / videoWidth.toFloat()
            val scaleY = screenHeight.toFloat() / videoHeight.toFloat()

            // Устанавливаем масштаб, чтобы видео заполнило весь экран
            videoView.scaleX = scaleX
            videoView.scaleY = scaleY
        }

        videoView.start()

        // Зацикливание видео
        videoView.setOnCompletionListener { videoView.start() }

        // Начинаем проверку интернета
        handler.post(checkInternetRunnable)
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val networkCapabilities = connectivityManager.activeNetwork ?: return false
            val actNw = connectivityManager.getNetworkCapabilities(networkCapabilities) ?: return false
            return actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || actNw.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) || actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkInternetRunnable)
        videoView.stopPlayback()
        Log.d(TAG, "Экран без интернета уничтожен")
    }
}