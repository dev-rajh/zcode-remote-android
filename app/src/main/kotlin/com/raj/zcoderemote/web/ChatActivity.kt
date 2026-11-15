package com.raj.zcoderemote.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewCompat
import com.raj.zcoderemote.MainActivity
import com.raj.zcoderemote.R
import com.raj.zcoderemote.data.CredentialHealth
import com.raj.zcoderemote.data.DeviceStore
import com.raj.zcoderemote.data.PairedDevice
import com.raj.zcoderemote.link.RemoteLink
import com.raj.zcoderemote.notify.MonitorService
import com.raj.zcoderemote.relay.RelayProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the ZCode web remote control SPA for the selected paired desktop, in
 * a plain (non-Compose) activity: the SPA does not paint inside a Compose
 * `AndroidView` host (its renderer stalls after the first frame), but renders
 * fine in a classic activity-hosted WebView.
 *
 * Before loading a device whose link embeds V1 credentials (`hash`), a
 * one-shot relay credential probe runs (see [RelayProbe]); a stale pairing
 * gets a friendly re-pair screen instead of the SPA's English AUTH_FAILED
 * terminal page. Current sid-only links load directly — the SPA manages its
 * own account authentication.
 *
 * The underlying WebView is a process-singleton (see [RemoteWebViewHolder])
 * so it survives navigating away and keeps working in the background.
 */
class ChatActivity : ComponentActivity() {

    private lateinit var store: DeviceStore
    private lateinit var webView: WebView
    private lateinit var webContainer: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var overlay: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayBody: TextView
    private lateinit var overlayScan: Button
    private lateinit var overlayOpen: Button
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private var popupDialog: android.app.Dialog? = null
    private var popupWebView: WebView? = null

    private var device: PairedDevice? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var precheckJob: Job? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser ?: return@registerForActivityResult
            pendingFileChooser = null
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
        }

    private val rescanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        store = DeviceStore(this)
        DeviceStore.migrateLegacyConnection(this, store)
        device = store.get(intent?.getStringExtra(EXTRA_DEVICE_ID) ?: "")
            ?: store.get(store.lastDeviceId() ?: "")
            ?: store.devices().firstOrNull()
        if (device == null) {
            // Nothing paired yet: hand back to the Compose link screen.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        webView = RemoteWebViewHolder.getOrCreate(this, device!!.url)
        webContainer = findViewById(R.id.chat_web_container)
        attachWebViewToContainer()
        overlay = findViewById(R.id.chat_overlay)
        overlayTitle = findViewById(R.id.overlay_title)
        overlayBody = findViewById(R.id.overlay_body)
        overlayScan = findViewById(R.id.btn_overlay_scan)
        overlayOpen = findViewById(R.id.btn_overlay_open)
        progress = findViewById(R.id.chat_progress)
        titleView = findViewById(R.id.chat_title)

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        findViewById<ImageButton>(R.id.btn_switch_device).apply {
            if (store.devices().size > 1) {
                setOnClickListener { showDeviceSwitcher() }
            } else {
                visibility = View.GONE
            }
        }
        overlayScan.setOnClickListener {
            hideOverlay()
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_LINK_SCREEN, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            rescanLauncher.launch(intent)
        }
        overlayOpen.setOnClickListener {
            hideOverlay()
            attachWebViewToContainer()
        }

        webView.webChromeClient = createAppChromeClient(
            onProgress = {
                progress.progress = it
                progress.visibility = if (it in 1..99) View.VISIBLE else View.GONE
            },
            onFileChooser = { callback, params ->
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = callback
                try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    pendingFileChooser = null
                    false
                }
            },
            onOpenWindow = { resultMsg ->
                android.util.Log.d("ZCodeFlow", "window-open → popup dialog")
                runOnUiThread { showPopup(resultMsg) }
            },
        )
        RemoteWebViewHolder.onMainFrameError = { message ->
            if (RemoteWebViewHolder.currentUrl() == device?.url) {
                runOnUiThread {
                    showOverlay("Couldn't load the page", message, showRescan = false, showOpen = true)
                }
            }
        }
        RemoteWebViewHolder.onRendererGone = {
            runOnUiThread { recreate() }
        }

        // Pull-to-refresh reloads the current page (app-like feel).
        webContainer.setOnRefreshListener {
            webView.reload()
        }
        webContainer.setColorSchemeResources(android.R.color.holo_blue_dark)

        // Keep the toolbar below the status bar under edge-to-edge enforcement
        // (Android 15+ / OEM builds).
        val toolbar = findViewById<View>(R.id.chat_toolbar)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        refreshHeader()
        savedInstanceState?.let { webView.restoreState(it) }
        startPrecheckIfNeeded()
        MonitorService.start(this)
        requestNotificationPermissionIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        val device = device ?: return
        // Some OEM WebViews (ColorOS/MTK) drop a loadUrl issued while the
        // activity was in the background; retry on foreground — but only when
        // the page never started (don't clobber an in-progress OAuth page).
        webView.post {
            if (RemoteWebViewHolder.current() === webView && isDestroyedNot() &&
                (webView.url.isNullOrBlank() || webView.url == "about:blank")
            ) {
                RemoteWebViewHolder.ensureLoaded(device.url)
            }
        }
    }

    override fun onDestroy() {
        precheckJob?.cancel()
        RemoteWebViewHolder.onMainFrameError = null
        RemoteWebViewHolder.onRendererGone = null
        if (isFinishing) RemoteWebViewHolder.markAttached(false)
        activityScope.cancel()
        super.onDestroy()
    }

    /** Moves the shared WebView into this activity's container, then loads. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebViewToContainer() {
        val device = device ?: return
        (webView.parent as? ViewGroup)?.removeView(webView)
        webContainer.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Load only once the view is attached to a window: some OEM WebView
        // builds (ColorOS/MTK) drop a loadUrl issued before first attachment
        // and the page stays at about:blank.
        webView.post {
            if (RemoteWebViewHolder.current() === webView && isDestroyedNot()) {
                RemoteWebViewHolder.ensureLoaded(device.url)
            }
        }
    }

    private fun isDestroyedNot(): Boolean = !isDestroyed && !isFinishing

    /** Probe (when the link embeds V1 credentials), then load or show re-pair UI. */
    private fun startPrecheckIfNeeded() {
        val device = device ?: return
        val alreadyLoaded = RemoteWebViewHolder.currentUrl() == device.url
        val probeable = RemoteLink.parse(device.url)?.hasEmbeddedCredentials == true
        if (alreadyLoaded || !probeable) {
            hideOverlay()
            attachWebViewToContainer()
            return
        }
        showOverlay("Checking pairing…", "Talking to the relay…", showRescan = false, showOpen = false)
        precheckJob?.cancel()
        precheckJob = activityScope.launch {
            val result = RelayProbe.probe(device.url)
            when (result) {
                RelayProbe.Result.VALID -> {
                    store.setCredentialStatus(device.id, CredentialHealth.STATUS_VALID)
                    hideOverlay()
                    attachWebViewToContainer()
                }
                RelayProbe.Result.STALE -> {
                    store.setCredentialStatus(device.id, CredentialHealth.STATUS_STALE)
                    showOverlay(
                        "Pairing expired for “${device.name}”",
                        "The relay rejected this device's credentials — usually because " +
                            "pairing was reset on the desktop or the link was regenerated. " +
                            "Scan the latest QR code to re-pair.",
                        showRescan = true,
                        showOpen = true,
                    )
                }
                RelayProbe.Result.UNKNOWN -> {
                    // Network trouble or inconclusive: don't block, let the page retry.
                    store.setCredentialStatus(device.id, CredentialHealth.STATUS_UNKNOWN)
                    hideOverlay()
                    attachWebViewToContainer()
                }
            }
        }
    }

    private fun refreshHeader() {
        titleView.text = device?.name ?: "ZCode Remote"
    }

    /**
     * Hosts a REAL popup WebView in a dialog for the SPA's OAuth flow
     * (window.open → WebViewTransport). The opener relationship is native, so
     * the auth code reaches the main SPA via postMessage exactly like in a
     * browser. Cookies/DOM storage are process-global, so the session the
     * popup establishes is shared with the main page.
     */
    private fun showPopup(resultMsg: android.os.Message) {
        dismissPopup()
        val dialog = android.app.Dialog(this, android.R.style.Theme_NoTitleBar_Fullscreen)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF161616.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            setBackgroundColor(0xFF1D1D1D.toInt())
        }
        bar.addView(android.widget.TextView(this).apply {
            text = "Sign in"
            setTextColor(0xFFF5F5F5.toInt())
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(android.widget.ImageButton(this).apply {
            setBackgroundColor(0x00000000)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Close sign-in"
            setOnClickListener { dismissPopup() }
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
        })
        val popupProgress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                6.dpToPx(),
            )
        }
        val container = FrameLayout(this)
        val popup = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setBackgroundColor(0xFF161616.toInt())
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host
                    android.util.Log.d("ZCodeFlow", "popup nav: ${request.url} host=$host")
                    if ("https".equals(request.url.scheme, true) && RemoteLink.isAllowedHost(host)) {
                        return false
                    }
                    return try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, request.url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    android.util.Log.d("ZCodeFlow", "popup finished: $url")
                    // The OAuth chain ends at the CLI callback page whose JS
                    // postMessages the code to the opener (the main SPA). Give
                    // it a moment, then close the popup.
                    if (url != null && url.contains("/oauth/") && url.contains("callback")) {
                        view?.postDelayed({ dismissPopup() }, 2500)
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    android.util.Log.d("ZCodeFlow", "popup renderer gone")
                    dismissPopup()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    popupProgress.progress = newProgress
                    popupProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }

                override fun onCloseWindow(window: WebView?) {
                    runOnUiThread { dismissPopup() }
                }
            }
        }
        container.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(popupProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6.dpToPx()))
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dialog.setContentView(root)
        dialog.setOnDismissListener { popupWebView = null }
        dialog.show()
        popupDialog = dialog
        popupWebView = popup

        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null) {
            dismissPopup()
            return
        }
        transport.webView = popup
        resultMsg.sendToTarget()
    }

    private fun dismissPopup() {
        popupWebView?.let { runCatching { it.destroy() } }
        popupWebView = null
        popupDialog?.let { runCatching { if (it.isShowing) it.dismiss() } }
        popupDialog = null
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showOverlay(title: String, body: String, showRescan: Boolean, showOpen: Boolean) {
        overlayTitle.text = title
        overlayBody.text = body
        overlayScan.visibility = if (showRescan) View.VISIBLE else View.GONE
        overlayOpen.visibility = if (showOpen) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
    }

    private fun showDeviceSwitcher() {
        val devices = store.devices()
        if (devices.size <= 1) {
            Toast.makeText(this, "Only one desktop is paired.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = devices.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Switch desktop")
            .setItems(names) { dialog, which ->
                val picked = devices.getOrNull(which) ?: return@setItems
                dialog.dismiss()
                store.setLastDeviceId(picked.id)
                device = picked
                refreshHeader()
                hideOverlay()
                attachWebViewToContainer()
                startPrecheckIfNeeded()
            }
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"

        fun start(context: Context, deviceId: String?) {
            context.startActivity(
                Intent(context, ChatActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }

        /** Chrome client factory shared with the WebView (progress + file chooser + window-open). */
        fun createAppChromeClient(
            onProgress: (Int) -> Unit,
            onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
            onOpenWindow: (android.os.Message) -> Unit,
        ): WebChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean = onFileChooser(filePathCallback, fileChooserParams)

            /**
             * The SPA's "Authorize in browser" flow opens the OAuth page via
             * window.open and later receives the auth code back via
             * postMessage from the popup (window.opener). Host a REAL popup
             * WebView in a dialog so the opener relationship — and thus
             * postMessage — works natively, exactly like a browser.
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                android.util.Log.d("ZCodeFlow", "onCreateWindow called")
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                onOpenWindow(resultMsg)
                return true
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("ZCodeFlow", "console: ${consoleMessage?.message()?.take(180)} @${consoleMessage?.sourceId()?.takeLast(60)}:${consoleMessage?.lineNumber()}")
                return true
            }
        }
    }
}
