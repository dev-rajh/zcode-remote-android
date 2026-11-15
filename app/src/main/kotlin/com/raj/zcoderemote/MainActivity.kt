package com.raj.zcoderemote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import com.raj.zcoderemote.data.ConnectionStore
import com.raj.zcoderemote.data.DeviceStore
import com.raj.zcoderemote.link.RemoteLink
import com.raj.zcoderemote.notify.MonitorService
import com.raj.zcoderemote.ui.theme.ZCodeRemoteTheme
import com.raj.zcoderemote.web.ChatActivity

/**
 * Router + Compose host for the link / scanner / settings screens. Pairing
 * chat itself lives in [ChatActivity] (a plain activity — the SPA does not
 * render inside a Compose AndroidView host).
 */
class MainActivity : ComponentActivity() {

    private var pendingEntry by mutableStateOf<NavKey?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = DeviceStore(this)
        DeviceStore.migrateLegacyConnection(this, store)
        resolveIntent(intent)

        val requestedEntry = when {
            intent?.getBooleanExtra(EXTRA_OPEN_LINK_SCREEN, false) == true -> Link
            intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true -> Settings
            else -> null
        }
        if (requestedEntry == null && pendingEntry == null && store.devices().isNotEmpty()) {
            // Linked and launched normally: go straight to the chat activity.
            ChatActivity.start(this, store.lastDeviceId())
            finish()
            return
        }

        if (store.devices().isNotEmpty() && ConnectionStore(this).monitorEnabled) {
            MonitorService.start(this)
        }

        setContent {
            ZCodeRemoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val startEntry = remember {
                        requestedEntry
                            ?: pendingEntry
                            ?: if (store.devices().isNotEmpty()) Settings else Link
                    }
                    MainNavigation(
                        startEntry = startEntry,
                        pendingEntry = pendingEntry,
                        onPendingConsumed = { pendingEntry = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resolveIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_LINK_SCREEN, false) == true) {
            pendingEntry = Link
        } else if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) {
            pendingEntry = Settings
        }
    }

    /** Route shared/opened remote-control links into the chat screen. */
    private fun resolveIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return

        val link = RemoteLink.parse(text)
        if (link != null) {
            val store = DeviceStore(this)
            val device = store.addOrReplace(link)
            requestNotificationPermissionIfNeeded()
            if (store.devices().isNotEmpty() && ConnectionStore(this).monitorEnabled) {
                MonitorService.start(this)
            }
            ChatActivity.start(this, device.id)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_LINK_SCREEN = "open_link_screen"
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
}
