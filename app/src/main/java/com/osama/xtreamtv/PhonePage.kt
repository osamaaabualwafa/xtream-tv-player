package com.osama.xtreamtv

import android.graphics.Bitmap
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Package 5 (approved 2026-11-13): a tiny web page the TV serves on the home Wi-Fi, for the phone.
 * Only the address with this page's one-time code answers ([token]); nothing goes through the internet.
 * [handle] gets the path after the code, the method and the posted fields, and returns the page.
 * Logins, keys and addresses are never logged.
 */
class PhonePage(
    private val port: Int,
    private val lifetimeMs: Long,
    private val handle: (path: String, method: String, fields: Map<String, String>, cookie: String?) -> Response,
    /** The one-time code in the address (a fixed one keeps remembered phones working). */
    val token: String = newToken(),
) {
    class Response(val html: String, val contentType: String = "text/html; charset=utf-8", val setCookie: String? = null)

    @Volatile private var socket: ServerSocket? = null
    @Volatile var endsAt = SystemClock.elapsedRealtime() + lifetimeMs

    /** The address to scan, or null when the TV isn't on a home network. */
    fun address(): String? = localAddress()?.let { "http://$it:$port/p/$token" }

    fun start(): Boolean {
        val s = runCatching { ServerSocket(port).apply { soTimeout = 1000; reuseAddress = true } }.getOrNull() ?: return false
        socket = s
        Thread({
            while (socket === s && SystemClock.elapsedRealtime() < endsAt) {
                val client = try { s.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                Thread({ runCatching { serve(client) }; runCatching { client.close() } }, "phone-page-client").apply { isDaemon = true }.start()
            }
            runCatching { s.close() }
        }, "phone-page").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        val s = socket
        socket = null
        runCatching { s?.close() }
    }

    val running get() = socket != null && SystemClock.elapsedRealtime() < endsAt

    private fun serve(client: java.net.Socket) {
        client.soTimeout = 15000
        val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
        val request = input.readLine() ?: return
        var length = 0
        var cookie: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            if (line.startsWith("Cookie:", ignoreCase = true)) cookie = line.substringAfter(':').split(';').map { it.trim() }.firstOrNull { it.startsWith("xtv=") }?.removePrefix("xtv=")
        }
        val method = request.substringBefore(' ')
        val full = request.split(' ').getOrNull(1) ?: ""
        val path = full.substringBefore('?')
        val query = full.substringAfter('?', "")
        val prefix = "/p/$token"
        val res = if (path != prefix && !path.startsWith("$prefix/")) {
            Response(page("Not found", "<p>This address isn't open. On the TV, open the phone page again for a new code.</p>"))
        } else {
            val body = if (method == "POST") {
                val buf = CharArray(length.coerceIn(0, 16_384))
                var read = 0
                while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
                String(buf, 0, read)
            } else query
            val fields = body.split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            handle(path.removePrefix(prefix).ifEmpty { "/" }, method, fields, cookie)
        }
        val bytes = res.html.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            val head = StringBuilder("HTTP/1.0 200 OK\r\nContent-Type: ${res.contentType}\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            res.setCookie?.let { head.append("Set-Cookie: xtv=$it; Path=/; Max-Age=2592000; HttpOnly; SameSite=Strict\r\n") }
            write(head.append("\r\n").toString().toByteArray())
            write(bytes)
            flush()
        }
    }

    companion object {
        fun newToken(): String {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }

        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        fun qr(text: String, size: Int): Bitmap {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
            )
            val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        fun page(title: String, content: String, script: String = "") = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>$title · Xtream TV</title><style>
body{font-family:system-ui,-apple-system,Roboto,sans-serif;background:#f6f7f9;color:#16191d;margin:0;padding:20px 16px;max-width:520px;margin:auto}
.top{font-size:13px;color:#6b7480}h1{font-size:21px;font-weight:600;margin:8px 0}p{font-size:15px;line-height:1.5;color:#3d4550}
label{display:block;font-size:13px;color:#6b7480;margin-top:10px}
input{box-sizing:border-box;width:100%;font-size:17px;padding:12px;border:1px solid #c5ccd4;border-radius:12px;margin:4px 0 4px}
button{width:100%;font-size:17px;font-weight:600;padding:14px;border:0;border-radius:12px;background:#2f6fd6;color:#fff;margin-top:12px}
.small{font-size:13px;color:#6b7480}.bad{color:#b3261e}.ok{color:#1a7f4b}.status{font-size:16px;padding:12px;border-radius:12px;background:#eef2f6;margin-top:14px}
</style></head><body><div class=top>Xtream TV &middot; on your TV</div>$content<script>$script</script></body></html>"""
    }
}
