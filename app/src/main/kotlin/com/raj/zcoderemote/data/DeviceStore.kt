package com.raj.zcoderemote.data

import android.content.Context
import android.content.SharedPreferences
import com.raj.zcoderemote.link.RemoteLink
import org.json.JSONArray
import org.json.JSONObject

/** One paired desktop. [id] is the pairing's `sid`; [mid] is the stable machine id. */
data class PairedDevice(
    val id: String,
    val url: String,
    val name: String,
    val linkedAtMillis: Long,
    val mid: String? = null,
) {
    companion object {
        /**
         * Whether [this] and [other] refer to the same desktop: same sid, or
         * same stable mid (pairing resets rotate sid/hash but keep mid).
         */
        fun sameMachine(a: PairedDevice?, b: PairedDevice?): Boolean {
            if (a == null || b == null) return false
            if (a.id == b.id) return true
            return !a.mid.isNullOrBlank() && a.mid == b.mid
        }
    }
}

/** Credential health of a stored pairing, probed before opening a device. */
object CredentialHealth {
    const val STATUS_UNKNOWN = "unknown"
    const val STATUS_VALID = "valid"
    const val STATUS_STALE = "stale"
}

/**
 * Store of paired desktops (multi-machine support). Re-scanning a QR adds a
 * new entry or refreshes an existing one (same sid or same mid), mirroring
 * the reference client's behavior. Credentials are kept encrypted.
 */
class DeviceStore(context: Context) {

    private val securePrefs: SharedPreferences =
        context.getSharedPreferences("zcode_devices_prefs", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences("zcode_settings", Context.MODE_PRIVATE)

    fun devices(): List<PairedDevice> {
        val json = securePrefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val url = SecureStore.decrypt(o.getString("url")) ?: return@mapNotNull null
                PairedDevice(
                    id = o.getString("id"),
                    url = url,
                    name = o.optString("name").ifBlank { "Desktop" },
                    linkedAtMillis = o.optLong("linkedAt", 0L),
                    mid = o.optString("mid").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): PairedDevice? = devices().firstOrNull { it.id == id }

    fun lastDeviceId(): String? = settingsPrefs.getString(KEY_LAST_DEVICE, null)

    fun setLastDeviceId(id: String) {
        settingsPrefs.edit().putString(KEY_LAST_DEVICE, id).apply()
    }

    /** Adds or replaces (same sid or same mid = same desktop). Replaced entries lose their credential status. */
    fun addOrReplace(link: RemoteLink): PairedDevice {
        val sid = link.deviceSid ?: "legacy-${System.currentTimeMillis()}"
        val device = PairedDevice(
            id = sid,
            url = link.url,
            name = link.deviceName ?: "Desktop",
            linkedAtMillis = System.currentTimeMillis(),
            mid = link.deviceMid,
        )
        val existing = devices()
        val next = existing.filter { !PairedDevice.sameMachine(it, device) }
        // Replaced entries' credential status is invalidated too.
        val removedIds = existing.filter { PairedDevice.sameMachine(it, device) }.map { it.id }
        val editor = settingsPrefs.edit()
        for (oldId in removedIds) {
            editor.remove(KEY_CRED_STATUS + oldId).remove(KEY_CRED_PROBED + oldId)
        }
        editor.apply()

        saveDevices(next + device)
        setLastDeviceId(device.id)
        return device
    }

    fun remove(id: String) {
        saveDevices(devices().filterNot { it.id == id })
        settingsPrefs.edit()
            .remove(KEY_CRED_STATUS + id)
            .remove(KEY_CRED_PROBED + id)
            .apply()
        if (lastDeviceId() == id) {
            val fallback = devices().firstOrNull()?.id
            if (fallback != null) setLastDeviceId(fallback) else settingsPrefs.edit().remove(KEY_LAST_DEVICE).apply()
        }
    }

    fun credentialStatus(id: String): String =
        settingsPrefs.getString(KEY_CRED_STATUS + id, CredentialHealth.STATUS_UNKNOWN)
            ?: CredentialHealth.STATUS_UNKNOWN

    fun credentialProbedAt(id: String): Long = settingsPrefs.getLong(KEY_CRED_PROBED + id, 0L)

    fun setCredentialStatus(id: String, status: String) {
        settingsPrefs.edit()
            .putString(KEY_CRED_STATUS + id, status)
            .putLong(KEY_CRED_PROBED + id, System.currentTimeMillis())
            .apply()
    }

    private fun saveDevices(devices: List<PairedDevice>) {
        val array = JSONArray()
        for (d in devices) {
            array.put(
                JSONObject()
                    .put("id", d.id)
                    .put("url", SecureStore.encrypt(d.url))
                    .put("name", d.name)
                    .put("linkedAt", d.linkedAtMillis)
                    .put("mid", d.mid ?: ""),
            )
        }
        securePrefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    companion object {
        private const val KEY_DEVICES = "devices"
        private const val KEY_LAST_DEVICE = "lastDeviceId"
        private const val KEY_CRED_STATUS = "credStatus."
        private const val KEY_CRED_PROBED = "credProbedAt."

        /**
         * One-time migration: import the pre-multi-device single connection
         * (legacy `zcode_secure_prefs` entry) as a paired device.
         */
        fun migrateLegacyConnection(context: Context, store: DeviceStore) {
            if (store.devices().isNotEmpty()) return
            val legacyPrefs = context.getSharedPreferences("zcode_secure_prefs", Context.MODE_PRIVATE)
            val blob = legacyPrefs.getString("connectionUrl", null) ?: return
            val url = SecureStore.decrypt(blob) ?: return
            val link = RemoteLink.parse(url) ?: return
            store.addOrReplace(link)
        }
    }
}
