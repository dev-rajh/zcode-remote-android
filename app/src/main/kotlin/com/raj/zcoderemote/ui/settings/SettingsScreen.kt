package com.raj.zcoderemote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.raj.zcoderemote.data.ConnectionStore
import com.raj.zcoderemote.data.CredentialHealth
import com.raj.zcoderemote.data.DeviceStore
import com.raj.zcoderemote.notify.MonitorService
import com.raj.zcoderemote.web.RemoteWebViewHolder
import java.net.URI
import java.text.DateFormat
import java.util.Date

/**
 * Paired desktops (with credential health), notification preferences, and
 * unlinking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenChat: () -> Unit,
    onRelink: () -> Unit,
    onAllDevicesRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { DeviceStore(context) }
    val settings = remember { ConnectionStore(context) }
    var devices by remember { mutableStateOf(store.devices()) }
    var lastDeviceId by remember { mutableStateOf(store.lastDeviceId()) }

    var monitorEnabled by remember { mutableStateOf(settings.monitorEnabled) }
    var notifyCompleted by remember { mutableStateOf(settings.notifyOnCompleted) }
    var startOnBoot by remember { mutableStateOf(settings.startOnBoot) }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Settings") }) }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    if (devices.isEmpty()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Paired desktops", style = MaterialTheme.typography.titleMedium)
                            Text("No desktops paired yet.")
                            OutlinedButton(onClick = onRelink) { Text("Link a desktop") }
                        }
                    } else {
                        devices.forEachIndexed { index, device ->
                            if (index > 0) HorizontalDivider()
                            DeviceRow(
                                device = device,
                                isLast = device.id == lastDeviceId,
                                credStatus = store.credentialStatus(device.id),
                                probedAt = store.credentialProbedAt(device.id),
                                onRemove = {
                                    store.remove(device.id)
                                    devices = store.devices()
                                    lastDeviceId = store.lastDeviceId()
                                    RemoteWebViewHolder.destroy()
                                    if (devices.isEmpty()) {
                                        MonitorService.stop(context)
                                        onAllDevicesRemoved()
                                    }
                                },
                                onSwitch = {
                                    store.setLastDeviceId(device.id)
                                    lastDeviceId = device.id
                                },
                            )
                        }
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedButton(onClick = onRelink, modifier = Modifier.fillMaxWidth()) {
                                Text("Link another desktop")
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ToggleRow(
                        title = "Approval & input notifications",
                        subtitle = "Notify when ZCode needs approval or is waiting for input",
                        checked = monitorEnabled,
                        onCheckedChange = {
                            monitorEnabled = it
                            settings.monitorEnabled = it
                            if (it) MonitorService.start(context) else MonitorService.stop(context)
                        },
                    )
                    HorizontalDivider()
                    ToggleRow(
                        title = "Task finished notifications",
                        subtitle = "Notify when a task completes",
                        checked = notifyCompleted,
                        onCheckedChange = {
                            notifyCompleted = it
                            settings.notifyOnCompleted = it
                        },
                    )
                    HorizontalDivider()
                    ToggleRow(
                        title = "Start monitoring on boot",
                        subtitle = "Resume background notifications after reboot",
                        checked = startOnBoot,
                        onCheckedChange = {
                            startOnBoot = it
                            settings.startOnBoot = it
                        },
                    )
                }
            }

            settings.lastEvent?.let { lastEvent ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Last event seen", style = MaterialTheme.typography.titleSmall)
                        Text(lastEvent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (devices.isNotEmpty()) {
                Button(onClick = onOpenChat, modifier = Modifier.fillMaxWidth()) {
                    Text("Open chat")
                }
            }

            Text(
                "Pairing links are short-lived — if the relay rejects a device, " +
                    "re-scan the latest QR code from its desktop. Credentials are " +
                    "stored encrypted and never leave this device except to talk " +
                    "to the relay.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: com.raj.zcoderemote.data.PairedDevice,
    isLast: Boolean,
    credStatus: String,
    probedAt: Long,
    onRemove: () -> Unit,
    onSwitch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(device.name)
                    if (isLast) append("  · active")
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val host = runCatching { URI(device.url).host }.getOrNull() ?: device.url
            Text(
                host,
                style = MaterialTheme.typography.bodySmall,
            )
            val statusLine = when (credStatus) {
                CredentialHealth.STATUS_VALID -> "credentials ok"
                CredentialHealth.STATUS_STALE -> "pairing expired — re-scan"
                else -> if (probedAt > 0) "not checked yet" else "not checked"
            }
            val probedSuffix = if (probedAt > 0) {
                " · checked " + DateFormat.getTimeInstance().format(Date(probedAt))
            } else {
                ""
            }
            Text(statusLine + probedSuffix, style = MaterialTheme.typography.bodySmall)
        }
        if (!isLast) {
            TextButton(onClick = onSwitch) { Text("Use") }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove ${device.name}")
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
