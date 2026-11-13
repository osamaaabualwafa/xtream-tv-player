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
        var agent: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            if (line.startsWith("User-Agent:", ignoreCase = true)) agent = line.substringAfter(":").trim().take(300)
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
            // The phone's browser name ("iPhone", "Samsung phone" in Settings); never logged.
            handle(path.removePrefix(prefix).ifEmpty { "/" }, method, fields + ("_ua" to agent.orEmpty()), cookie)
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

        /** Small line icons for the phone pages, in the TV's icon style (thin rounded lines). */
        fun icon(name: String): String {
            val d = when (name) {
                "phone" -> "<rect x='6.75' y='2.75' width='10.5' height='18.5' rx='1.75'/><path d='M10.75 18h2.5'/>"
                "playlist" -> "<path d='M4 6h12M4 11h12M4 16h7'/><path d='M18 14v6M15 17h6'/>"
                "key" -> "<circle cx='8' cy='15' r='4'/><path d='M11 12l8-8M16 7l2 2M14 9l2 2'/>"
                "search" -> "<circle cx='11' cy='11' r='6.5'/><path d='M16 16l4.5 4.5'/>"
                "remote" -> "<rect x='7' y='2.75' width='10' height='18.5' rx='3'/><circle cx='12' cy='9' r='2.2'/><path d='M10 15h4M10 18h4'/>"
                "play" -> "<path d='M8 5.5v13l10-6.5z'/>"
                "open" -> "<path d='M9 6l6 6-6 6'/>"
                "ok" -> "<path d='M5 12.5l4.5 4.5L19 7.5'/>"
                "menu" -> "<path d='M4 7h16M4 12h16M4 17h16'/>"
                "filter" -> "<path d='M4 6h16l-6 7.5V19l-4-2v-3.5z'/>"
                "sort" -> "<path d='M7 4v16M4 17l3 3 3-3M17 20V4M14 7l3-3 3 3'/>"
                "flag" -> "<path d='M5 21V4M5 4h11l-2 4 2 4H5'/>"
                "bad" -> "<path d='M6.5 6.5l11 11M17.5 6.5l-11 11'/>"
                else -> ""
            }
            return "<svg class=ic viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'>$d</svg>"
        }

        /** A result line with a tick or a cross (owner, 2026-11-13: the result shows on the phone too). */
        fun result(ok: Boolean, html: String) = "<div class='res ${if (ok) "good" else "bad"}'>${icon(if (ok) "ok" else "bad")}<span>$html</span></div>"

        private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
        private fun rgba(c: Int) = "rgba(${(c shr 16) and 255},${(c shr 8) and 255},${c and 255},${"%.2f".format(java.util.Locale.US, ((c ushr 24) and 255) / 255f)})"

        /**
         * The TV's current theme as page colours (owner, 2026-11-13: the phone pages match the theme in use):
         * the accent, text colours, glass panels and the background scene. A plain dark look when no screen is open.
         */
        private fun themeCss(): String {
            val a = PhonePanel.currentActivity()
            fun color(attr: Int, fallback: Int): Int {
                if (a == null) return fallback
                val v = android.util.TypedValue()
                if (!a.theme.resolveAttribute(attr, v, true)) return fallback
                return if (v.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) v.data
                else runCatching { androidx.core.content.ContextCompat.getColor(a, v.resourceId) }.getOrDefault(fallback)
            }
            val accent = color(R.attr.appAccent, 0xFF3D8BFD.toInt())
            // Words on the accent: dark on a light accent (Aurora's mint), white on a deep one.
            val onAccent = if (android.graphics.Color.luminance(accent) > 0.4f) androidx.core.graphics.ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.82f) else 0xFFFFFFFF.toInt()
            val text = color(R.attr.appText, 0xFFFFFFFF.toInt())
            val sub = color(R.attr.appTextSecondary, 0xFFA0A8B0.toInt())
            val light = android.graphics.Color.luminance(text) < 0.5f
            val fill = color(R.attr.appGlassFill, if (light) 0x14000000 else 0x17FFFFFF)
            val stroke = color(R.attr.appGlassStroke, if (light) 0x1F000000 else 0x29FFFFFF)
            val prefs = a?.let { Prefs(it) }
            val (top, bottom) = if (prefs != null && prefs.themeIndex == Appearance.THEME_GLASS) {
                val sw = Appearance.SCENE_SWATCHES[prefs.glassScene.coerceIn(0, Appearance.SCENE_SWATCHES.size - 1)]
                sw[1] to sw[2]
            } else {
                val surface = color(R.attr.appSurface, 0xFF1C232B.toInt())
                surface to androidx.core.graphics.ColorUtils.blendARGB(surface, accent, 0.18f)
            }
            return ":root{--acc:${hex(accent)};--on:${hex(onAccent)};--text:${hex(text)};--sub:${hex(sub)};" +
                "--fill:${rgba(fill)};--stroke:${rgba(stroke)};--top:${hex(top)};--bottom:${hex(bottom)};" +
                "--good:${if (light) "#1A7F4B" else "#5FD38D"};--bad:${if (light) "#B3261E" else "#F2A7A7"};color-scheme:${if (light) "light" else "dark"}}"
        }

        /** A phone page in the TV's theme: [symbol] and [title] on top, then [content]. */
        fun page(title: String, content: String, script: String = "", symbol: String? = null, heading: String? = null) = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name=theme-color content="#000"><title>$title · Xtream TV</title><style>
${themeCss()}
*{box-sizing:border-box}html{min-height:100%;background:linear-gradient(160deg,var(--top),var(--bottom)) fixed}
body{font-family:system-ui,-apple-system,Roboto,sans-serif;color:var(--text);margin:0 auto;padding:22px 16px 40px;max-width:520px}
.top{font-size:11px;letter-spacing:1.6px;text-transform:uppercase;color:var(--sub)}
h1{font-size:23px;font-weight:600;margin:6px 0 4px;display:flex;align-items:center;gap:10px}h1 .ic{width:26px;height:26px;color:var(--acc)}
p{font-size:15px;line-height:1.5;color:var(--sub);margin:6px 0}b{color:var(--text)}a{color:var(--acc)}
.ic{width:20px;height:20px;flex:none}
.card{background:var(--fill);border:1px solid var(--stroke);border-radius:20px;padding:14px 16px;margin-top:14px;-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px)}
label{display:block;font-size:13px;color:var(--sub);margin:12px 2px 2px}
input{width:100%;font-size:17px;padding:13px 14px;color:var(--text);background:var(--fill);border:1px solid var(--stroke);border-radius:14px;margin:4px 0;outline:none}
input:focus{border-color:var(--acc);box-shadow:0 0 0 3px color-mix(in srgb,var(--acc) 30%,transparent)}input::placeholder{color:var(--sub);opacity:.8}
button{width:100%;font-size:17px;font-weight:600;padding:14px;border:0;border-radius:16px;background:var(--acc);color:var(--on);margin-top:14px;display:flex;gap:8px;align-items:center;justify-content:center}
button:active{transform:scale(.98)}
.small{font-size:13px;color:var(--sub);margin-top:14px}.bad{color:var(--bad)}.ok{color:var(--good)}
.res{display:flex;gap:10px;align-items:center;font-size:16px;line-height:1.4;padding:13px 14px;border-radius:16px;background:var(--fill);border:1px solid var(--stroke);margin-top:14px}
.res .ic{width:24px;height:24px}.res.good .ic{color:var(--good)}.res.bad .ic{color:var(--bad)}.res.wait .ic{color:var(--acc)}
</style></head><body><div class=top>Xtream TV &middot; on your TV</div>${if (heading != null) "<h1>${symbol?.let { icon(it) } ?: ""}$heading</h1>" else ""}$content<script>$script</script></body></html>"""
    }
}
