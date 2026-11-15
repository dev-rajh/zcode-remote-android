package com.raj.zcoderemote.ui.link

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raj.zcoderemote.data.DeviceStore
import com.raj.zcoderemote.link.RemoteLink
import com.raj.zcoderemote.notify.MonitorService

/**
 * First-run screen: link this device to a desktop ZCode remote-control link
 * by scanning its QR code, pasting the link, or typing the token.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(
    onLinked: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore(context) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun connect(linkText: String) {
        val link = RemoteLink.parse(linkText)
        if (link == null) {
            error = "That doesn't look like a ZCode remote-control link or token."
            return
        }
        busy = true
        error = null
        store.addOrReplace(link)
        requestNotificationPermissionIfNeeded()
        MonitorService.start(context)
        onLinked()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "ZCode Remote",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Chat with your ZCode sessions and get notified when a task " +
                "needs your approval or input.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Link your device", style = MaterialTheme.typography.titleMedium)
                Text(
                    "On your desktop, open ZCode's Remote Control and copy the " +
                        "link (or QR code) it shows, then:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan QR code")
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (text.isBlank()) {
                            error = "Clipboard is empty."
                        } else {
                            input = text
                            connect(text)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Paste link & connect")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("or paste link / token here") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { connect(input) },
                    enabled = input.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect")
                }
            }
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
