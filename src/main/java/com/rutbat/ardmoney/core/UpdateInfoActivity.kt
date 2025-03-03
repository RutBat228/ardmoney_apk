package com.rutbat.ardmoney.core

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.rutbat.ardmoney.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateInfoActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var backButton: Button
    private var downloadId: Long = -1
    private var isInstalling = false
    private val TAG = "UpdateInfoActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = getColor(R.color.green_500)
        setContentView(R.layout.activity_update_info)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        webView = findViewById(R.id.update_webview)
        progressBar = findViewById(R.id.update_progress_bar)
        backButton = findViewById(R.id.button_back)

        val url = intent.getStringExtra("update_url") ?: "https://ardmoney.ru/api/update_info.php"

        setupWebView(url)
        setupBackButton()
    }

    private fun setupWebView(url: String) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                updateButtonStates()
                Log.d(TAG, "Page started loading: $url")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = View.GONE
                updateButtonStates()
                Log.d(TAG, "Page finished loading: $url")
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.endsWith(".apk")) {
                    downloadApk(url)
                    return true
                }
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.endsWith(".apk")) {
                    downloadApk(url)
                    return true
                }
                return false
            }
        }

        webView.loadUrl(url)
    }

    private fun setupBackButton() {
        backButton.setOnClickListener {
            navigateToMainActivity()
        }
    }

    private fun downloadApk(url: String) {
        val downloadManager = getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading APK")
            .setDescription("Downloading update for ArdMoney")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, "downloads", "update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "Started downloading APK from $url with ID: $downloadId")

        monitorDownload(downloadId)
    }

    private fun monitorDownload(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadManager = getSystemService(DownloadManager::class.java)
            var isCompleted = false
            while (!isCompleted) {
                val query = DownloadManager.Query().setFilterById(id)
                downloadManager.query(query).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                                val uri = Uri.parse(uriString)
                                val apkFile = File(requireNotNull(uri.path))
                                withContext(Dispatchers.Main) {
                                    installApk(apkFile)
                                }
                                isCompleted = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                Log.e(TAG, "Download failed with reason: $reason")
                                isCompleted = true
                            }
                        }
                    }
                }
                if (!isCompleted) {
                    delay(1000)
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists() || isInstalling) {
            Log.e(TAG, "APK file does not exist at: ${apkFile.absolutePath} or installation already in progress")
            return
        }

        Log.d(TAG, "APK file exists at: ${apkFile.absolutePath}, size: ${apkFile.length()} bytes")
        isInstalling = true
        val apkUri = FileProvider.getUriForFile(
            this,
            "com.rutbat.ardmoney.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            Log.d(TAG, "APK installation intent started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start APK installation: ${e.message}", e)
            isInstalling = false
        }
    }

    @Deprecated("Deprecated in API 33, replaced with ActivityResult API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        isInstalling = false
        if (resultCode == RESULT_OK) {
            Log.d(TAG, "APK installation completed successfully")
            finish()
        } else {
            Log.e(TAG, "APK installation failed or was cancelled")
        }
    }

    private fun updateButtonStates() {
        backButton.isEnabled = progressBar.visibility == View.GONE
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("targetUrl", "https://ardmoney.ru")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    @Deprecated("Deprecated in API 30, use OnBackPressedDispatcher")
    override fun onBackPressed() {
        super.onBackPressed()
        navigateToMainActivity()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}