package moe.momokko.sillytavernapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "ST_PREFS"
        private const val KEY_IDLE = "idle_timeout_min"
        private const val REPLY_CHANNEL = "ai_reply"
        private const val REPLY_NOTIF_ID = 2
        private const val NOTIF_PERM_CODE = 1001
    }

    private lateinit var webView: WebView
    private lateinit var loading: View
    private val url = "http://127.0.0.1:${NodeService.PORT}/"
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    private val BUSY_POLL_MS = 5000L
    private var idleRunnable: Runnable? = null
    private var busyPoll: Runnable? = null

    // True only while the activity is stopped. The busy check runs an async
    // evaluateJavascript callback; if the user re-foregrounds before it fires,
    // this guards against pausing a now-visible WebView.
    private var stopped = false

    private fun idleTimeoutMs(): Long =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_IDLE, 0) * 60_000L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        loading = findViewById(R.id.loading)

        // targetSdk 36 forces edge-to-edge; pad the content by the system bars and
        // display cutout so the status/nav bars don't overlap ST's toolbars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> {
                        // Keep the local SillyTavern app in the WebView; send anything
                        // else to an in-app Custom Tab (X top-left, menu top-right).
                        if (uri.host == "127.0.0.1" || uri.host == "localhost") {
                            false
                        } else {
                            openExternal(uri); true
                        }
                    }

                    "mailto", "tel", "sms" -> {
                        openExternal(uri); true
                    }
                    // blob:, data:, javascript:, etc. must stay in the WebView.
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                injectAppScripts()
            }
        }
        webView.addJavascriptInterface(WebAppBridge(), "STAndroid")
        requestNotificationPermission()

        if (!hasAllFilesAccess()) {
            requestAllFilesAccess()
        } else {
            startEverything()
        }
    }

    private fun startEverything() {
        if (started) return
        started = true
        cancelIdleShutdown()
        val svc = Intent(this, NodeService::class.java)
        ContextCompat.startForegroundService(this, svc)
        pollUntilReady()
    }

    private fun pollUntilReady() {
        Thread {
            while (true) {
                NodeService.nodeExitCode?.let { code ->
                    main.post { showServerError(code) }
                    return@Thread
                }
                try {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1000; readTimeout = 1000
                    }
                    c.responseCode  // any response means the server is up
                    c.disconnect()
                    main.post {
                        webView.loadUrl(url)
                        loading.visibility = View.GONE
                    }
                    return@Thread
                } catch (_: Exception) {
                    Thread.sleep(500)
                }
            }
        }.start()
    }

    private fun showServerError(code: Int) {
        findViewById<View>(R.id.progress)?.visibility = View.GONE
        findViewById<android.widget.TextView>(R.id.loadingText)?.text =
            getString(R.string.server_stopped, code)
    }

    private fun injectAppScripts() {
        for (name in listOf("webview-hooks.js", "st-app-extension.js")) {
            val js = assets.open(name).bufferedReader().use { it.readText() }
            webView.evaluateJavascript(js, null)
        }
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        try {
            if (scheme == "http" || scheme == "https") {
                CustomTabsIntent.Builder().build().launchUrl(this, uri)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                // No app can handle this URL; ignore.
            }
        }
    }

    // ---- All-Files access ----
    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFilesAccess() && loading.visibility == View.VISIBLE && webView.url == null) {
            startEverything()
        }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        cancelIdleShutdown()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        // Decide whether we can pause + arm idle shutdown, deferring while a generation runs.
        checkBusyThenIdle()
    }

    private fun checkBusyThenIdle() {
        webView.evaluateJavascript("window.__ST_BUSY || 0") { value ->
            // The activity returned to the foreground while this callback was
            // pending; do not pause a visible WebView or arm shutdown.
            if (!stopped) return@evaluateJavascript
            val busy = value?.trim()?.toIntOrNull() ?: 0
            if (busy > 0) {
                // Keep rendering so the in-flight generation completes; re-check shortly.
                busyPoll = Runnable { checkBusyThenIdle() }
                main.postDelayed(busyPoll!!, BUSY_POLL_MS)
            } else {
                // Idle: always pause the WebView to save battery (Node stays alive).
                webView.onPause()
                webView.pauseTimers()
                // Only kill the process if the user opted into auto-exit (timeout > 0).
                val timeout = idleTimeoutMs()
                if (timeout > 0L) {
                    idleRunnable = Runnable {
                        startService(
                            Intent(this, NodeService::class.java)
                                .setAction(NodeService.ACTION_STOP)
                        )
                    }
                    main.postDelayed(idleRunnable!!, timeout)
                }
            }
        }
    }

    private fun cancelIdleShutdown() {
        idleRunnable?.let { main.removeCallbacks(it) }
        busyPoll?.let { main.removeCallbacks(it) }
        idleRunnable = null
        busyPoll = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ---- Web -> native bridge (exposed as window.STAndroid) ----
    inner class WebAppBridge {
        @JavascriptInterface
        fun getIdleTimeoutMinutes(): Int =
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_IDLE, 0)

        @JavascriptInterface
        fun setIdleTimeoutMinutes(min: Int) {
            val v = if (min < 0) 0 else min
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_IDLE, v).apply()
        }

        @JavascriptInterface
        fun notifyAiResponded(title: String, body: String) {
            main.post { postAiNotification(title, body) }
        }
    }

    private fun postAiNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(
                    REPLY_CHANNEL, "AI replies",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, REPLY_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(REPLY_NOTIF_ID, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; drop silently.
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM_CODE)
        }
    }
}
