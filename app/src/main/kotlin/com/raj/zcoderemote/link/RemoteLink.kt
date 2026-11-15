package com.raj.zcoderemote.link

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A validated ZCode remote-control link, stored as the full URL to open in
 * the WebView.
 *
 * Two link formats exist (matching the ZCode web app's own routing):
 *
 *  - **Mobile pairing flow** (what the desktop QR code encodes), served at
 *    `/remote/v4`: `?sid=<deviceSid>&hash=<passHash>&t=<timestamp>`
 *    plus optional `mid`, `name`, `app_version`, `theme`. The SPA routes any
 *    `/remote/v4` URL to this flow, so `remoteControlToken` must NOT appear
 *    here.
 *  - **Legacy window-token flow**, served at `/web-remote`:
 *    `?remoteControlToken=<token>&relayOrigin=<origin>`.
 */
data class RemoteLink(val url: String) {

    /** True when the link uses the legacy remoteControlToken flow. */
    val isLegacyTokenFlow: Boolean
        get() = url.substringBefore('?', url).let { path ->
            !path.endsWith("/remote/v4") && QUERY_TOKEN in url
        }

    /**
     * Whether the link carries V1 self-contained credentials (`hash`). When
     * absent (the current desktop QR strips `hash`/`t`), the SPA handles
     * authentication itself and no native credential probe is possible.
     */
    val hasEmbeddedCredentials: Boolean
        get() = !queryParam(QUERY_HASH).isNullOrBlank()

    /** Desktop session id (`sid` param) — unique per pairing, rotates on reset. */
    val deviceSid: String? get() = queryParam(QUERY_SID)

    /** Desktop machine id (`mid` param) — stable across pairing resets. */
    val deviceMid: String? get() = queryParam("mid")

    /** Desktop display name (`name` param). */
    val deviceName: String? get() = queryParam("name")?.takeIf { it.isNotBlank() }

    companion object {
        private const val DEFAULT_HOST = "https://zcode.z.ai"
        private const val MOBILE_PATH = "/remote/v4"
        private const val LEGACY_PATH = "/web-remote"
        private const val QUERY_SID = "sid"
        private const val QUERY_HASH = "hash"
        private const val QUERY_TIMESTAMP = "t"
        private const val QUERY_TOKEN = "remoteControlToken"

        /** Official hosts the pairing page may live on. */
        private val ALLOWED_HOST_SUFFIXES = listOf(".z.ai")
        private val ALLOWED_HOSTS = setOf(
            "z.ai",
            "zcode.z.ai",
            "www.zcode.z.ai",
            "zcode.chatglm.site",
        )

        fun isAllowedHost(host: String?): Boolean {
            val normalized = host?.trim()?.lowercase() ?: return false
            return normalized in ALLOWED_HOSTS || ALLOWED_HOST_SUFFIXES.any { normalized.endsWith(it) }
        }

        /**
         * Accepts a full URL (from QR scan, shared link) or a bare query
         * string (hand-copied params). Returns null for anything that
         * doesn't carry a recognizable parameter set.
         */
        fun parse(input: String): RemoteLink? {
            val text = input.trim()
            if (text.isEmpty()) return null

            val (basePath, rawParams) = extractPathAndParams(text) ?: return null
            if (rawParams.isEmpty()) return null

            // The current desktop QR/link carries only sid (+ mid/name/
            // app_version) — `hash`/`t` are deliberately stripped so
            // credentials never enter a scannable image. Older links were
            // self-contained (sid+hash+t). Both are accepted; `sid` alone is
            // what today's desktop emits.
            val isMobileFlow = !rawParams[QUERY_SID]?.trim().isNullOrEmpty()
            val isLegacyFlow = rawParams.containsKey(QUERY_TOKEN)

            val path = when {
                isMobileFlow -> if (basePath != null && basePath.startsWith("/remote")) basePath else MOBILE_PATH
                isLegacyFlow -> LEGACY_PATH // token flow only works at /web-remote
                else -> return null
            }
            val query = rawParams.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            return RemoteLink("$DEFAULT_HOST$path?$query")
        }

        /**
         * Splits input into (path, params). Handles full URLs and bare query
         * strings (which may contain `://` inside a relayOrigin value, so the
         * URL branch requires a leading scheme).
         */
        private fun extractPathAndParams(text: String): Pair<String?, Map<String, String>>? {
            return if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
                val uri = runCatching { URI(text) }.getOrNull() ?: return null
                // Only accept ZCode web app hosts; the rebuilt URL always
                // targets them anyway, so a scanned host is never navigated to.
                if (!isAllowedHost(uri.host)) return null
                val rawQuery = uri.rawQuery ?: return null
                uri.rawPath to decodeParams(rawQuery)
            } else if (text.contains("=")) {
                val withoutQueryMark = text.substringAfter('?', text)
                null to decodeParams(withoutQueryMark)
            } else {
                null
            }
        }

        private fun RemoteLink.queryParam(name: String): String? {
            val rawQuery = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
            return decodeParams(rawQuery)[name]?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun decodeParams(rawQuery: String): Map<String, String> =
            rawQuery.split("&")
                .mapNotNull { part ->
                    val pieces = part.split("=", limit = 2)
                    if (pieces.size == 2) {
                        val key = runCatching { URLDecoder.decode(pieces[0], "UTF-8") }.getOrNull() ?: return@mapNotNull null
                        val value = runCatching { URLDecoder.decode(pieces[1], "UTF-8") }.getOrNull() ?: return@mapNotNull null
                        key to value
                    } else {
                        null
                    }
                }
                .toMap()
    }
}
