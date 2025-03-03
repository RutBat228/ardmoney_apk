package com.rutbat.ardmoney.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.messaging.FirebaseMessaging
import com.rutbat.ardmoney.R
import com.rutbat.ardmoney.config.AppConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data: Intent? = result.data
                val uri = data?.data
                fileUploadCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                fileUploadCallback?.onReceiveValue(arrayOf(uri))
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                Log.d(TAG, "All permissions granted, opening file chooser")
                openFileChooser()
            } else {
                val deniedPermissions = permissions.filter { !it.value }.keys.joinToString(", ")
                Log.e(TAG, "Permissions denied: $deniedPermissions, cannot open file chooser")
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
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

        setupWebView()
        updateNavigationVisibility()
        setupBottomNavigation()

        val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "user_login") {
                updateNavigationVisibility()
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
                Log.w(TAG, "Нет интернет-соединения при обновлении")
            }
        }

        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isSwipeRefreshAllowed) {
                        val canScrollUp = webView.canScrollVertically(-1)
                        swipeRefreshLayout.isEnabled = !canScrollUp
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

        checkForUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("user_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        webView.destroy()
    }

    private fun updateNavigationVisibility() {
        val sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val userLoginFromPrefs = sharedPref.getString("user_login", null)
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(AppConfig.WEBVIEW_URL)
        val userFromCookie = cookies?.let {
            val cookieMap = it.split(";").map { cookie ->
                cookie.trim().split("=").let { it[0] to it.getOrNull(1) }
            }.toMap()
            cookieMap["user"]
        }
        val isLoggedIn = userLoginFromPrefs != null || userFromCookie != null
        Log.d(TAG, "Checking navigation visibility: userLoginFromPrefs = $userLoginFromPrefs, userFromCookie = $userFromCookie, isLoggedIn = $isLoggedIn")
        bottomNavigationView.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        bottomNavigationView.invalidate()
    }

    private fun checkForUpdates() {
        Log.d(TAG, "Начало проверки обновлений")
        if (!isNetworkAvailable()) {
            Log.w(TAG, "Нет интернет-соединения, пропускаем проверку обновлений")
            return
        }

        val queue = Volley.newRequestQueue(this)
        val url = AppConfig.CHECK_VERSION_URL
        Log.d(TAG, "URL для проверки обновлений: $url")

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось получить версию приложения: ${e.message}", e)
            return
        }

        val jsonBody = JSONObject().apply {
            put("version", versionName)
        }

        val request = object : JsonObjectRequest(Request.Method.POST, url, jsonBody,
            { response ->
                Log.d(TAG, "Получен ответ от сервера: $response")
                try {
                    val updateNeeded = response.getBoolean("updateNeeded")
                    if (updateNeeded) {
                        val newVersion = response.getString("newVersion")
                        val downloadUrl = response.getString("downloadUrl")
                        if (versionName != null) {
                            showUpdateDialog(versionName, newVersion, downloadUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка разбора ответа сервера: ${e.message}", e)
                }
            },
            { error ->
                Log.e(TAG, "Ошибка при проверке обновлений: ${error.message}", error)
            }) {
            override fun getHeaders(): Map<String, String> {
                return mapOf("Content-Type" to "application/json")
            }
        }
        queue.add(request)
    }

    private fun showUpdateDialog(currentVersion: String, newVersion: String, downloadUrl: String) {
        val updateInfoUrl = "${AppConfig.WEBVIEW_URL}/api/update_info.php?version=${URLEncoder.encode(currentVersion, "UTF-8")}"
        Log.d(TAG, "URL для диалога обновления: $updateInfoUrl")

        val dialogView = LayoutInflater.from(this).inflate(R.layout.custom_update_dialog, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<TextView>(R.id.update_title).text = getString(R.string.update_available)
        dialogView.findViewById<TextView>(R.id.update_message).text = getString(R.string.update_message, newVersion)

        dialogView.findViewById<Button>(R.id.update_button).setOnClickListener {
            Log.d(TAG, "Пользователь нажал 'Обновить', открываем UpdateInfoActivity с URL: $updateInfoUrl")
            val intent = Intent(this, UpdateInfoActivity::class.java).apply {
                putExtra("update_url", updateInfoUrl)
            }
            startActivity(intent)
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.later_button).setOnClickListener {
            Log.d(TAG, "Пользователь нажал 'Позже'")
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            ViewCompat.setOnApplyWindowInsetsListener(decorView) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        }
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
                Log.w(TAG, "Нет интернет-соединения для глубокой ссылки: $url")
            }
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
            if (isUpdatingNavigation) return@setOnNavigationItemSelectedListener true

            val menuItemView = bottomNavigationView.findViewById<View>(item.itemId)
            menuItemView?.let {
                animateNavigationItem(it, item.itemId == R.id.nav_add)
            }

            val url = when (item.itemId) {
                R.id.nav_home -> AppConfig.WEBVIEW_URL
                R.id.nav_search -> "${AppConfig.WEBVIEW_URL}/search_montaj.php"
                R.id.nav_add -> "${AppConfig.WEBVIEW_URL}/montaj.php"
                R.id.nav_profile -> "${AppConfig.WEBVIEW_URL}/user.php"
                R.id.nav_navigard -> "${AppConfig.WEBVIEW_URL}/navigard/"
                else -> return@setOnNavigationItemSelectedListener false
            }

            if (isNetworkAvailable()) {
                webView.loadUrl(url)
                isUpdatingNavigation = true
                bottomNavigationView.selectedItemId = item.itemId
                bottomNavigationView.menu.setGroupCheckable(0, true, true)
                isUpdatingNavigation = false
            } else {
                showNoInternetScreen(url)
                Log.w(TAG, "Нет интернет-соединения для навигации: $url")
                return@setOnNavigationItemSelectedListener true
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
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100L)
                    .start()
            }
            .start()
    }

    private fun showNoInternetScreen(targetUrl: String? = null) {
        val intent = Intent(this, NoInternetActivity::class.java).apply {
            putExtra("targetUrl", targetUrl ?: webView.url)
        }
        startActivity(intent)
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
            val intent = Intent(this, NoInternetActivity::class.java).apply {
                putExtra("targetUrl", webView.url ?: AppConfig.WEBVIEW_URL)
            }
            startActivity(intent)
            Log.w(TAG, "Нет интернет-соединения при возобновлении")
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
            val noInternetIntent = Intent(this, NoInternetActivity::class.java).apply {
                putExtra("targetUrl", webView.url ?: AppConfig.WEBVIEW_URL)
            }
            startActivity(noInternetIntent)
            Log.w(TAG, "Нет интернет-соединения при запуске")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun subscribeToFirebaseTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic("all")
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "Не удалось подписаться на тему 'all'", task.exception)
                }
            }
    }

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // Разрешаем смешанный контент
            cacheMode = WebSettings.LOAD_DEFAULT // Используем стандартный режим кэширования
            databaseEnabled = true // Включаем поддержку баз данных
            loadsImagesAutomatically = true // Автоматическая загрузка изображений
            setGeolocationEnabled(false) // Включаем геолокацию, если требуется
        }

        webView.addJavascriptInterface(AndroidJsInterface(this), "AndroidInterface")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString() ?: return false
                return if (!url.contains("ardmoney.ru")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } else {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loadingOverlay.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                Log.d(TAG, "Страница начала загружаться: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingOverlay.visibility = View.GONE
                progressBar.visibility = View.GONE
                Log.d(TAG, "Страница завершила загрузку: $url")
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

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "Ошибка WebView: ${error?.description}")
                loadingOverlay.visibility = View.GONE
                progressBar.visibility = View.GONE
                if (!isNetworkAvailable()) {
                    val intent = Intent(this@MainActivity, NoInternetActivity::class.java).apply {
                        putExtra("targetUrl", request?.url.toString())
                    }
                    startActivity(intent)
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
        Log.d(TAG, "Saving user login: $login")
        getSharedPreferences("user_prefs", MODE_PRIVATE).edit()
            .putString("user_login", login)
            .apply()
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) sendTokenToServer(login, task.result)
            else Log.e(TAG, "Ошибка получения FCM-токена", task.exception)
        }
    }

    private fun sendTokenToServer(login: String, token: String) {
        Thread {
            try {
                val url = URL("${AppConfig.WEBVIEW_URL}/api/set_token.php")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                val params = "login=${URLEncoder.encode(login, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"
                connection.outputStream.use { os ->
                    os.write(params.toByteArray(Charsets.UTF_8))
                }
                connection.inputStream.use { }
                Log.d(TAG, "Токен успешно отправлен на сервер")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки токена на сервер", e)
            }
        }.start()
    }

    private fun checkPermissionsAndOpenFileChooser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "Android 14+: Using PickVisualMedia for file selection")
            openMediaPicker()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            val permissionsToRequest = permissions.filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions for Android 13: ${permissionsToRequest.joinToString()}")
                permissionLauncher.launch(permissionsToRequest)
            } else {
                Log.d(TAG, "All permissions already granted for Android 13, opening file chooser")
                openFileChooser()
            }
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting READ_EXTERNAL_STORAGE for Android 9")
                permissionLauncher.launch(arrayOf(permission))
            } else {
                Log.d(TAG, "READ_EXTERNAL_STORAGE already granted for Android 9, opening file chooser")
                openFileChooser()
            }
        }
    }

    private fun openMediaPicker() {
        Log.d(TAG, "Opening media picker for Android 14+")
        pickMediaLauncher.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
        )
    }

    private fun openFileChooser() {
        Log.d(TAG, "Opening file chooser for Android 13 and below")
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun showExitDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_exit, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val exitButton = dialogView.findViewById<Button>(R.id.exitButton)

        exitButton.setOnClickListener {
            Log.d(TAG, "Exit button clicked")
            finish()
            dialog.dismiss()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    // Внутренний класс для JavaScript-интерфейса
    private class AndroidJsInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun setUserLogin(login: String) {
            val sharedPref = activity.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val currentLogin = sharedPref.getString("user_login", null)
            Log.d(activity.TAG, "setUserLogin called: new login = $login, current login = $currentLogin")
            if (currentLogin != login) {
                activity.saveUserLogin(login)
                activity.updateNavigationVisibility()
            }
        }

        @JavascriptInterface
        fun clearUserLogin() {
            Log.d(activity.TAG, "clearUserLogin called")
            activity.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit()
                .remove("user_login")
                .apply()
            activity.updateNavigationVisibility()
        }

        @JavascriptInterface
        fun disableSwipeRefresh() {
            activity.swipeRefreshLayout.isEnabled = false
            Log.d(activity.TAG, "SwipeRefreshLayout отключен через JS интерфейс")
        }

        @JavascriptInterface
        fun enableSwipeRefresh() {
            activity.swipeRefreshLayout.isEnabled = true
            Log.d(activity.TAG, "SwipeRefreshLayout включен через JS интерфейс")
        }

        @JavascriptInterface
        fun onNavigationItemSelected(itemId: String) {
            when (itemId) {
                "nav_home" -> activity.bottomNavigationView.selectedItemId = R.id.nav_home
                "nav_search" -> activity.bottomNavigationView.selectedItemId = R.id.nav_search
                "nav_add" -> activity.bottomNavigationView.selectedItemId = R.id.nav_add
                "nav_profile" -> activity.bottomNavigationView.selectedItemId = R.id.nav_profile
                "nav_navigard" -> activity.bottomNavigationView.selectedItemId = R.id.nav_navigard
            }
            val selectedView = activity.bottomNavigationView.findViewById<View>(activity.bottomNavigationView.selectedItemId)
            selectedView?.let { activity.animateNavigationItem(it, itemId == "nav_add") }
            Log.d(activity.TAG, "Выбран элемент навигации: $itemId")
        }
    }
}