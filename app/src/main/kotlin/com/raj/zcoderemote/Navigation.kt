package com.raj.zcoderemote

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.raj.zcoderemote.data.DeviceStore
import com.raj.zcoderemote.link.RemoteLink
import com.raj.zcoderemote.notify.MonitorService
import com.raj.zcoderemote.ui.link.LinkScreen
import com.raj.zcoderemote.ui.scanner.QrScannerScreen
import com.raj.zcoderemote.ui.settings.SettingsScreen
import com.raj.zcoderemote.web.ChatActivity

@Composable
fun MainNavigation(
    startEntry: NavKey,
    pendingEntry: NavKey?,
    onPendingConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val deviceStore = remember { DeviceStore(context) }
    val backStack = rememberNavBackStack(startEntry)
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun acceptLink(link: RemoteLink) {
        val device = deviceStore.addOrReplace(link)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        MonitorService.start(context)
        ChatActivity.start(context, device.id)
        (context as? Activity)?.finish()
    }

    LaunchedEffect(pendingEntry) {
        pendingEntry?.let {
            backStack.clear()
            backStack.add(it)
            onPendingConsumed()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryProvider =
            entryProvider {
                entry<Link> {
                    LinkScreen(
                        onLinked = {
                            ChatActivity.start(context, deviceStore.lastDeviceId())
                            (context as? Activity)?.finish()
                        },
                        onScanQr = { backStack.add(Scanner) },
                    )
                }
                entry<Scanner> {
                    QrScannerScreen(
                        onLinkParsed = { link -> acceptLink(link) },
                        onClose = { backStack.removeLastOrNull() },
                    )
                }
                entry<Settings> {
                    SettingsScreen(
                        onOpenChat = { ChatActivity.start(context, deviceStore.lastDeviceId()) },
                        onRelink = { backStack.add(Link) },
                        onAllDevicesRemoved = {
                            MonitorService.stop(context)
                            backStack.clear()
                            backStack.add(Link)
                        },
                    )
                }
            },
    )
}
