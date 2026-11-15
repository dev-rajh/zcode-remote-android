package com.raj.zcoderemote.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewCompat
import com.raj.zcoderemote.link.RemoteLink

/**
 * Process-wide WebView singleton hosting the ZCode web remote control UI.
 *
 * The WebView is intentionally owned by the application context rather than
 * the activity: when the UI is closed (or the activity is destroyed) the page
 * keeps running, holds its WebSocket connection to the relay, and keeps
 * calling the notification shim — which is what makes background approval
 * notifications work while [com.raj.zcoderemote.notify.MonitorService] keeps
 * the process alive.
 */
object RemoteWebViewHolder {

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var loadedUrl: String? = null

    /** True while the WebView is visually attached to the chat screen. */
    @Volatile
    var isAttached: Boolean = false
        private set

    /** Hook for the chat screen to surface main-frame load failures. */
    @Volatile
    var onMainFrameError: ((String) -> Unit)? = null

    /** Hook invoked when the WebView renderer crashes (host should rebuild). */
    @Volatile
    var onRendererGone: (() -> Unit)? = null

    /** Hook invoked on page load completion (host may inspect/return from OAuth). */
    @Volatile
    var onPageFinishedHook: ((String) -> Unit)? = null

    fun current(): WebView? = webView

    /** URL the shared WebView is currently holding, if any. */
    fun currentUrl(): String? = loadedUrl

    fun markAttached(attached: Boolean) {
        isAttached = attached
        val view = webView
        if (view == null) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (attached) {
            // Chromium's async invalidations don't always trigger a draw when
            // the WebView is hosted inside Compose's AndroidView — SPA frames
            // stall until real touch input. Pump explicit invalidations while
            // the chat screen is showing.
            handler.removeCallbacks(drawPump)
            handler.post(drawPump)
        } else {
            handler.removeCallbacks(drawPump)
        }
    }

    private val drawPump = object : Runnable {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pumps = 0
        override fun run() {
            val view = webView
            if (!isAttached || view == null) return
            pumps++
            view.rootView?.invalidate()
            view.rootView?.requestLayout()
            handler.postDelayed(this, DRAW_PUMP_INTERVAL_MS)
        }
    }

    private const val DRAW_PUMP_INTERVAL_MS = 250L

    /**
     * Returns the shared WebView, creating it if needed. Must be called from
     * the main thread. Does NOT start loading — call [ensureLoaded] after the
     * WebView is attached to the window: some OEM WebView builds (ColorOS/MTK)
     * drop a `loadUrl` issued before first attachment, leaving the view at
     * `about:blank`.
     *
     * [context] should be the current Activity: WebViews created with an
     * application context render a black surface (Chromium never commits
     * content frames without an Activity-backed window). The instance is
     * still kept process-wide so background sessions survive navigation.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(context: Context, url: String): WebView {
        val existing = webView
        if (existing != null && loadedUrl == url) return existing
        existing?.destroy()

        val appContext = context.applicationContext
        val creationContext = context
        val isDebuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)

        val newView = WebView(creationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // The official page is pure https; never allow mixed-content downgrades.
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // The SPA's "Authorize in browser" flow uses window.open; with
            // multiple-windows disabled the call silently fails (blank page).
            // We enable it and route the opened target into this same WebView
            // in the chrome client, keeping OAuth cookies in the app session.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // Keep the renderer alive at high priority even when the app is
            // backgrounded: the default policy freezes/kills it, which would
            // break background pairing + notifications.
            setRendererPriorityPolicy(
                android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT,
                /* sacrificedWhenNotVisible = */ false,
            )
            setBackgroundColor(0xFF161616.toInt())

            CookieManager.getInstance().setAcceptCookie(true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host
                    android.util.Log.d("ZCodeFlow", "nav: ${request.url} host=$host scheme=${request.url.scheme}")
                    // Only real web URLs can go to the system browser; about:/
                    // javascript:/data: navigations stay in the WebView.
                    if (!"https".equals(request.url.scheme, true) && !"http".equals(request.url.scheme, true)) {
                        android.util.Log.d("ZCodeFlow", "nav swallowed (non-web scheme)")
                        return true
                    }
                    // Official hosts stay in the app; everything else goes to the browser.
                    if (RemoteLink.isAllowedHost(host)) {
                        android.util.Log.d("ZCodeFlow", "nav allowed in-webview: $host")
                        return false
                    }
                    android.util.Log.d("ZCodeFlow", "nav to system browser: $host")
                    return try {
                        appContext.startActivity(
                            Intent(Intent.ACTION_VIEW, request.url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    } catch (e: Exception) {
                        android.util.Log.d("ZCodeFlow", "no browser for $host: ${e.message}")
                        true // nowhere to open it; swallow the navigation
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    android.util.Log.d("ZCodeFlow", "error: ${request.url} ${error.description}")
                    // Only surface main-frame failures; sub-resources degrade in-page.
                    if (request.isForMainFrame) {
                        onMainFrameError?.invoke(error.description?.toString().orEmpty())
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    android.util.Log.d("ZCodeFlow", "page finished: $url")
                    if (url != null) onPageFinishedHook?.invoke(url)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    android.util.Log.d("ZCodeFlow", "RENDERER GONE detail=${detail.didCrash()}")
                    if (view === webView) {
                        view.destroy()
                        webView = null
                        loadedUrl = null
                    }
                    // Handled; the host activity recreates the WebView via
                    // [onRendererGone].
                    onRendererGone?.invoke()
                    return true
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                handleDownload(appContext, url, userAgent, contentDisposition, mimeType)
            }
        }

        // Install the Notification shim before any page script runs, on every
        // navigation, so the web app's permission prompt never appears and all
        // of its notifications are routed to the bridge.
        WebViewCompat.addDocumentStartJavaScript(
            newView,
            StateBridge.NOTIFICATION_SHIM_JS,
            setOf("https://*.z.ai", "https://z.ai"),
        )
        newView.addJavascriptInterface(StateBridge(), StateBridge.BRIDGE_NAME)

        webView = newView
        loadedUrl = null
        return newView
    }

    /**
     * Starts loading [url] if it isn't already the WebView's target. Call
     * AFTER the WebView is attached to a window (see [getOrCreate]).
     */
    fun ensureLoaded(url: String) {
        val view = webView ?: return
        if (loadedUrl == url && view.url != null && view.url != "about:blank") return
        android.util.Log.d("ZCodeFlow", "ensureLoaded: $url (viewUrl=${view.url}, loadedUrl=$loadedUrl)")
        loadedUrl = url
        view.loadUrl(url)
    }

    /** Records that the page navigated itself (e.g. OAuth window-open routing). */
    fun noteNavigated(url: String) {
        loadedUrl = url
    }

    /** Unconditionally loads [url] (bypasses the ensureLoaded dedupe check). */
    fun forceLoad(url: String) {
        val view = webView ?: return
        loadedUrl = url
        view.loadUrl(url)
    }

    fun reloadIfAny() {
        webView?.reload()
    }

    /** Destroys the shared WebView (used when unlinking). */
    fun destroy() {
        webView?.destroy()
        webView = null
        loadedUrl = null
    }

    private fun handleDownload(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloading from ZCode")
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .addRequestHeader(
                    "Cookie",
                    CookieManager.getInstance().getCookie(url).orEmpty(),
                )
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Chrome client factory shared with the chat screen (progress + file chooser). */
fun createAppChromeClient(
    onProgress: (Int) -> Unit,
    onFileChooser: (android.webkit.ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
): WebChromeClient = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = onFileChooser(filePathCallback, fileChooserParams)
}
