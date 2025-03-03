package com.rutbat.ardmoney.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.messaging.FirebaseMessaging
import com.rutbat.ardmoney.R
import com.rutbat.ardmoney.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingOverlay: View
    private lateinit var bottomNavigationView: BottomNavigationView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val TAG = "MainActivity"
    private var isUpdatingNavigation = false
    private var isSwipeRefreshAllowed = true
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        fileUploadCallback?.onReceiveValue(uri?.let { arrayOf(it) } ?: null)
        fileUploadCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            openMediaPicker()
        } else {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Log.w(TAG, "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = getColor(R.color.green_500)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        webView = findViewById(R.id.webview)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        bottomNavigationView = findViewById(R.id.bottomNavigationView)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        setupWebView()
        updateNavigationVisibility()
        setupBottomNavigation()

        val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "user_login") {
                runOnUiThread { updateNavigationVisibility() }
            }
        }
        sharedPref.registerOnSharedPreferenceChangeListener(prefsListener)

        handleDeepLink(intent)

        swipeRefreshLayout.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefreshLayout.isRefreshing = false
                showNoInternetScreen()
            }
        }

        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isSwipeRefreshAllowed) {
                        swipeRefreshLayout.isEnabled = !webView.canScrollVertically(-1)
                    }
                }
            }
            false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showExitDialog()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            checkInternetAndLoad(intent)
        }

        setupNetworkCallback()
        checkForUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("user_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        webView.destroy()
    }

    private fun setupNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    checkInternetAndLoad(intent)
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    showNoInternetScreen()
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun updateNavigationVisibility() {
        val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val userLoginFromPrefs = sharedPref.getString("user_login", null)
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(AppConfig.WEBVIEW_URL)
        val userFromCookie = cookies?.split(";")?.map { it.trim().split("=") }
            ?.associate { it[0] to it.getOrNull(1) }?.get("user")
        val isLoggedIn = userLoginFromPrefs != null || userFromCookie != null
        bottomNavigationView.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
    }

    private fun checkForUpdates() {
        if (!isNetworkAvailable()) return

        val queue = Volley.newRequestQueue(this)
        val url = AppConfig.CHECK_VERSION_URL
        val versionName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else { // API < 33
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to get package info: ${e.message}")
            return
        }

        val jsonBody = JSONObject().apply {
            put("version", versionName)
        }

        val request = object : JsonObjectRequest(Request.Method.POST, url, jsonBody,
            { response ->
                val updateNeeded = response.optBoolean("updateNeeded", false)
                if (updateNeeded) {
                    val newVersion = response.optString("newVersion", "")
                    val downloadUrl = response.optString("downloadUrl", "")
                    if (newVersion.isNotEmpty() && downloadUrl.isNotEmpty()) {
                        if (versionName != null) {
                            showUpdateDialog(versionName, newVersion, downloadUrl)
                        }
                    }
                }
            },
            { error ->
                Log.e(TAG, "Update check error: ${error.message}")
            }) {
            override fun getHeaders(): Map<String, String> {
                return mapOf("Content-Type" to "application/json")
            }
        }
        queue.add(request)
    }

    private fun showUpdateDialog(currentVersion: String, newVersion: String, downloadUrl: String) {
        val updateInfoUrl = "${AppConfig.WEBVIEW_URL}/api/update_info.php?version=${URLEncoder.encode(currentVersion, StandardCharsets.UTF_8.name())}"
        val dialogView = LayoutInflater.from(this).inflate(R.layout.custom_update_dialog, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<TextView>(R.id.update_title).text = getString(R.string.update_available)
        dialogView.findViewById<TextView>(R.id.update_message).text = getString(R.string.update_message, newVersion)

        dialogView.findViewById<Button>(R.id.update_button).setOnClickListener {
            startActivity(Intent(this, UpdateInfoActivity::class.java).putExtra("update_url", updateInfoUrl))
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.later_button).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        checkInternetAndLoad(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            val url = uri.toString()
            if (isNetworkAvailable()) {
                webView.loadUrl(url)
            } else {
                showNoInternetScreen(url)
            }
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { item ->
            if (isUpdatingNavigation) return@setOnItemSelectedListener true

            bottomNavigationView.findViewById<View>(item.itemId)?.let {
                animateNavigationItem(it, item.itemId == R.id.nav_add)
            }

            val url = when (item.itemId) {
                R.id.nav_home -> AppConfig.WEBVIEW_URL
                R.id.nav_search -> "${AppConfig.WEBVIEW_URL}/search_montaj.php"
                R.id.nav_add -> "${AppConfig.WEBVIEW_URL}/montaj.php"
                R.id.nav_profile -> "${AppConfig.WEBVIEW_URL}/user.php"
                R.id.nav_navigard -> "${AppConfig.WEBVIEW_URL}/navigard/"
                else -> return@setOnItemSelectedListener false
            }

            if (isNetworkAvailable()) {
                webView.loadUrl(url)
                isUpdatingNavigation = true
                bottomNavigationView.selectedItemId = item.itemId
                bottomNavigationView.menu.setGroupCheckable(0, true, true)
                isUpdatingNavigation = false
            } else {
                showNoInternetScreen(url)
            }
            true
        }
    }

    private fun animateNavigationItem(view: View, isAddButton: Boolean) {
        val scale = if (isAddButton) 1.25f else 1.15f
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(150L)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
            }
            .start()
    }

    private fun showNoInternetScreen(targetUrl: String = AppConfig.WEBVIEW_URL) {
        startActivity(Intent(this, NoInternetActivity::class.java).putExtra("targetUrl", targetUrl))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        updateNavigationVisibility()
        if (!isNetworkAvailable()) {
            showNoInternetScreen(webView.url ?: AppConfig.WEBVIEW_URL)
        }
    }

    private fun checkInternetAndLoad(intent: Intent?) {
        if (isNetworkAvailable()) {
            val targetUrl = intent?.getStringExtra("targetUrl") ?: ""
            if (targetUrl.isNotEmpty() && intent?.action == "${packageName}.OPEN_URL") {
                webView.loadUrl(targetUrl)
                updateNavigationState(targetUrl)
            } else if (webView.url == null) {
                val url = AppConfig.WEBVIEW_URL
                webView.loadUrl(url)
                updateNavigationState(url)
            }
            requestNotificationPermission()
            subscribeToFirebaseTopic()

            intent?.getBooleanExtra("checkUpdate", false)?.let { shouldCheck ->
                if (shouldCheck) checkForUpdates()
            }
        } else {
            showNoInternetScreen(webView.url ?: AppConfig.WEBVIEW_URL)
        }
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

    private fun requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun subscribeToFirebaseTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic("all")
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "Failed to subscribe to topic 'all': ${task.exception?.message}")
                }
            }
    }

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            setGeolocationEnabled(false)
        }

        webView.addJavascriptInterface(AndroidJsInterface(this), "AndroidInterface")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (!url.contains("ardmoney.ru")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } else {
                    false
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadingOverlay.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loadingOverlay.visibility = View.GONE
                progressBar.visibility = View.GONE
                if (swipeRefreshLayout.isRefreshing) swipeRefreshLayout.isRefreshing = false
                if (url?.contains("ardmoney.ru/result.php") == true) {
                    isSwipeRefreshAllowed = false
                    swipeRefreshLayout.isEnabled = false
                } else {
                    isSwipeRefreshAllowed = true
                    swipeRefreshLayout.isEnabled = !webView.canScrollVertically(-1)
                }
                updateNavigationState(url)
                updateNavigationVisibility()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.e(TAG, "WebView error: ${error.description}")
                loadingOverlay.visibility = View.GONE
                progressBar.visibility = View.GONE
                if (!isNetworkAvailable()) {
                    showNoInternetScreen(request.url.toString())
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                checkPermissionsAndOpenFileChooser()
                return true
            }
        }
    }

    private fun updateNavigationState(currentUrl: String?) {
        val navUrls = mapOf(
            R.id.nav_home to AppConfig.WEBVIEW_URL,
            R.id.nav_search to "${AppConfig.WEBVIEW_URL}/search_montaj.php",
            R.id.nav_add to "${AppConfig.WEBVIEW_URL}/montaj.php",
            R.id.nav_profile to "${AppConfig.WEBVIEW_URL}/user.php",
            R.id.nav_navigard to "${AppConfig.WEBVIEW_URL}/navigard/"
        )

        val baseHomeUrl = navUrls[R.id.nav_home] ?: "https://ardmoney.ru"
        val isHomePage = currentUrl?.startsWith(baseHomeUrl) == true || currentUrl?.startsWith("$baseHomeUrl/index.php") == true
        val matchingItemId = navUrls.entries.find { it.value == currentUrl }?.key

        isUpdatingNavigation = true
        if (matchingItemId != null && bottomNavigationView.selectedItemId == matchingItemId) {
            bottomNavigationView.menu.setGroupCheckable(0, true, true)
        } else if (isHomePage && bottomNavigationView.selectedItemId == R.id.nav_home) {
            bottomNavigationView.menu.setGroupCheckable(0, true, true)
        } else {
            bottomNavigationView.menu.setGroupCheckable(0, false, true)
        }
        isUpdatingNavigation = false
    }

    private fun saveUserLogin(login: String) {
        getSharedPreferences("user_prefs", MODE_PRIVATE).edit()
            .putString("user_login", login)
            .apply()
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) sendTokenToServer(login, task.result)
        }
    }

    private fun sendTokenToServer(login: String, token: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("${AppConfig.WEBVIEW_URL}/api/set_token.php")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                val params = "login=${URLEncoder.encode(login, StandardCharsets.UTF_8.name())}&token=${URLEncoder.encode(token, StandardCharsets.UTF_8.name())}"
                connection.outputStream.use { os ->
                    os.write(params.toByteArray(StandardCharsets.UTF_8))
                }
                connection.inputStream.use { }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send token to server: ${e.message}")
            }
        }
    }

    private fun checkPermissionsAndOpenFileChooser() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
        } else {
            openMediaPicker()
        }
    }

    private fun openMediaPicker() {
        pickMediaLauncher.launch(PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            .build())
    }

    private fun showExitDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_exit, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<Button>(R.id.exitButton).setOnClickListener {
            finish()
            dialog.dismiss()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private class AndroidJsInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun setUserLogin(login: String) {
            val sharedPref = activity.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val currentLogin = sharedPref.getString("user_login", null)
            if (currentLogin != login) {
                activity.saveUserLogin(login)
                activity.updateNavigationVisibility()
            }
        }

        @JavascriptInterface
        fun clearUserLogin() {
            activity.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit()
                .remove("user_login")
                .apply()
            activity.updateNavigationVisibility()
        }

        @JavascriptInterface
        fun disableSwipeRefresh() {
            activity.swipeRefreshLayout.isEnabled = false
        }

        @JavascriptInterface
        fun enableSwipeRefresh() {
            activity.swipeRefreshLayout.isEnabled = true
        }

        @JavascriptInterface
        fun onNavigationItemSelected(itemId: String) {
            val navId = when (itemId) {
                "nav_home" -> R.id.nav_home
                "nav_search" -> R.id.nav_search
                "nav_add" -> R.id.nav_add
                "nav_profile" -> R.id.nav_profile
                "nav_navigard" -> R.id.nav_navigard
                else -> return
            }
            activity.bottomNavigationView.selectedItemId = navId
            activity.bottomNavigationView.findViewById<View>(navId)?.let {
                activity.animateNavigationItem(it, itemId == "nav_add")
            }
        }
    }
}