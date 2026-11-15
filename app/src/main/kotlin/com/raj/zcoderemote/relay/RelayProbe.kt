package com.raj.zcoderemote.relay

import android.net.Uri
import android.util.Base64
import com.raj.zcoderemote.link.RemoteLink
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One-shot relay credential probe: performs the exact terminal-role auth
 * handshake the official mobile page performs, over a minimal dependency-free
 * WebSocket client (RFC6455 over TLS), to tell whether a stored sid/hash is
 * still accepted by the relay.
 *
 * Relay protocol (zcode-remote-hub): connect to `wss://<host>/ws?mid=<mid>`
 * then `auth_init{role:"terminal",device_sid}` → `auth_challenge{nonce}` →
 * `auth_response{proof=base64url(HMAC-SHA256(passHash, nonce|terminal|sid))}`
 * → `auth_ack` (valid) | `error{code:"AUTH_FAILED"|"WRONG_PARAM"}` (stale).
 *
 * IMPORTANT: a desktop allows only ONE active terminal, so never probe a
 * device this app is already connected to, and never probe periodically —
 * a probe would kick the in-use session offline. Probe once, right before
 * opening a device.
 */
object RelayProbe {

    enum class Result { VALID, STALE, UNKNOWN }

    private const val WS_OPCODE_TEXT = 0x1
    private const val WS_OPCODE_CLOSE = 0x8
    private const val WS_OPCODE_PONG = 0xA

    /** Synchronous; blocks up to [timeoutMs]. Must be called off the main thread. */
    fun probe(remoteUrl: String, timeoutMs: Int = 7_000): Result {
        val uri = runCatching { Uri.parse(remoteUrl) }.getOrNull() ?: return Result.UNKNOWN
        val host = uri.host ?: return Result.UNKNOWN
        if (!RemoteLink.isAllowedHost(host) || !"https".equals(uri.scheme, true)) {
            return Result.UNKNOWN
        }
        val sid = uri.getQueryParameter("sid")?.takeIf { it.isNotEmpty() } ?: return Result.UNKNOWN
        val hash = uri.getQueryParameter("hash")?.takeIf { it.isNotEmpty() } ?: return Result.UNKNOWN
        val mid = uri.getQueryParameter("mid")

        var socket: SSLSocket? = null
        return try {
            socket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
                connect(InetSocketAddress(host, 443), timeoutMs)
                soTimeout = timeoutMs
                tcpNoDelay = true
                // createSocket()+connect() skips SNI; CDNs require it. Set it explicitly.
                sslParameters = sslParameters.apply {
                    serverNames = listOf(javax.net.ssl.SNIHostName(host))
                }
            }
            runHandshake(socket, host, mid, sid, hash, timeoutMs)
        } catch (e: Exception) {
            Result.UNKNOWN
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun runHandshake(
        socket: SSLSocket,
        host: String,
        mid: String?,
        sid: String,
        hash: String,
        timeoutMs: Int,
    ): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream().buffered())

        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val wsPath = if (mid.isNullOrBlank()) "/ws" else "/ws?mid=${Uri.encode(mid)}"
        val handshake = "GET $wsPath HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $wsKey\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "User-Agent: ZCodeRemote/1.0\r\n\r\n"
        out.write(handshake.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val statusLine = readStatusLine(input, deadline) ?: return Result.UNKNOWN
        if (!statusLine.startsWith("HTTP/1.1 101") && !statusLine.startsWith("HTTP/1.0 101")) {
            return Result.UNKNOWN
        }

        val init = JSONObject().apply {
            put("type", "auth_init")
            put("role", "terminal")
            put("device_sid", sid)
            put(
                "meta",
                JSONObject().apply {
                    put("platform", "web")
                    put("version", "web")
                    put("name", "mobile-browser")
                },
            )
            // Official client sends client_ts in seconds (Math.floor(Date.now()/1000)).
            put("client_ts", System.currentTimeMillis() / 1000)
        }
        writeFrame(out, WS_OPCODE_TEXT, init.toString().toByteArray(Charsets.UTF_8))

        while (System.currentTimeMillis() < deadline) {
            val opcode = IntArray(1)
            val payload = readFrame(input, out, opcode) ?: continue // ping/pong/control handled
            if (opcode[0] == WS_OPCODE_CLOSE) return Result.UNKNOWN
            val msg = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: continue
            when (msg.optString("type")) {
                "auth_challenge" -> {
                    val nonce = msg.optString("nonce")
                    val response = JSONObject().apply {
                        put("type", "auth_response")
                        put("device_sid", sid)
                        put("proof", calculateProof(hash, nonce, sid))
                        put("client_ts", System.currentTimeMillis() / 1000)
                    }
                    writeFrame(out, WS_OPCODE_TEXT, response.toString().toByteArray(Charsets.UTF_8))
                }
                "auth_ack", "pair_status_ack" -> return Result.VALID
                "error" -> {
                    val code = msg.optString("code")
                    return if (code == "AUTH_FAILED" || code == "WRONG_PARAM") Result.STALE else Result.UNKNOWN
                }
            }
        }
        return Result.UNKNOWN
    }

    /** proof = base64url(HMAC-SHA256(passHash, `nonce|terminal|sid`)), unpadded. */
    private fun calculateProof(passHash: String, nonce: String, sid: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$nonce|terminal|$sid".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun readStatusLine(input: DataInputStream, deadline: Long): String? {
        val buffer = java.io.ByteArrayOutputStream()
        var last4 = 0L
        while (buffer.size() < 16 * 1024) {
            if (System.currentTimeMillis() > deadline) throw IOException("handshake timeout")
            val b = input.readUnsignedByte()
            buffer.write(b)
            last4 = ((last4 shl 8) or b.toLong()) and 0xFFFFFFFFL
            if (last4 == 0x0D0A0D0AL) {
                return buffer.toString(Charsets.ISO_8859_1).lineSequence().firstOrNull()
            }
        }
        return null
    }

    /**
     * Reads one complete WebSocket message; server frames are unmasked.
     * Replies to ping in place; returns null for frames the caller ignores.
     */
    private fun readFrame(
        input: DataInputStream,
        out: OutputStream,
        opcodeOut: IntArray,
    ): ByteArray? {
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val fin = b1 and 0x80 != 0
        val opcode = b1 and 0x0F
        val masked = b2 and 0x80 != 0
        var len = (b2 and 0x7F).toLong()
        if (len == 126L) {
            len = input.readUnsignedShort().toLong()
        } else if (len == 127L) {
            repeat(8) { input.read() } // probe scenario never sees large frames; drop length
            len = 1 shl 20
        }
        val mask = ByteArray(4)
        if (masked && input.read(mask) != 4) throw EOFException()
        val payload = ByteArray(len.toInt())
        input.readFully(payload)
        if (masked) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        }

        return when {
            opcode == 0x9 -> { // ping → pong
                runCatching { writeFrame(out, WS_OPCODE_PONG, payload) }
                null
            }
            opcode == WS_OPCODE_CLOSE -> {
                opcodeOut[0] = WS_OPCODE_CLOSE
                payload
            }
            opcode == WS_OPCODE_PONG -> null
            opcode == WS_OPCODE_TEXT && fin -> {
                opcodeOut[0] = WS_OPCODE_TEXT
                payload
            }
            opcode == 0x0 || (opcode == WS_OPCODE_TEXT && !fin) -> null // continuation: not expected here
            else -> null
        }
    }

    /** Client frames must be masked. Only small messages: ≤64KB lengths. */
    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val len = payload.size
        val head = java.io.ByteArrayOutputStream()
        head.write(0x80 or opcode)
        if (len < 126) {
            head.write(0x80 or len)
        } else if (len < 65536) {
            head.write(0x80 or 126)
            head.write((len shr 8) and 0xFF)
            head.write(len and 0xFF)
        } else {
            throw IOException("frame too large")
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        head.write(mask)
        out.write(head.toByteArray())
        val masked = Arrays.copyOf(payload, len)
        for (i in masked.indices) masked[i] = (masked[i].toInt() xor mask[i and 3].toInt()).toByte()
        out.write(masked)
        out.flush()
    }
}
