package com.rutbat.ardmoney.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rutbat.ardmoney.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NoInternetActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private val TAG = "NoInternetActivity"
    private lateinit var connectivityManager: ConnectivityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_no_internet)

        videoView = findViewById(R.id.videoView)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

            videoView.scaleX = scaleX
            videoView.scaleY = scaleY
        }

        videoView.start()

        // Зацикливание видео
        videoView.setOnCompletionListener { videoView.start() }

        // Начинаем проверку интернета
        startInternetCheck()
        registerNetworkCallback()
    }

    private fun startInternetCheck() {
        lifecycleScope.launch {
            while (true) {
                if (isNetworkAvailable()) {
                    navigateToMainActivity()
                    break
                }
                delay(2000)
            }
        }
    }

    private fun registerNetworkCallback() {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    navigateToMainActivity()
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun navigateToMainActivity() {
        val targetUrl = intent.getStringExtra("targetUrl")
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("targetUrl", targetUrl)
            putExtra("checkUpdate", true)
        }
        startActivity(returnIntent)
        finish()
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView.stopPlayback()
        Log.d(TAG, "Экран без интернета уничтожен")
    }
}