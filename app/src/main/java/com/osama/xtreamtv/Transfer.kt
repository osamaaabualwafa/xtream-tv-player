package com.osama.xtreamtv

import android.content.Context
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Moving a backup between TVs and phones (Package 6): a tiny server on the home Wi-Fi that answers only
 * at an address with a one-time code, only to the home network, and stops after one transfer or 5
 * minutes. Nothing goes through the internet; what travels is the locked backup file.
 */
object Transfer {

    /** TV to TV: the receiving TV listens here. */
    const val TV_PORT = 8095
    /** Phone: download a backup to the phone, or send one from it. */
    const val PHONE_PORT = 8094
    const val LIFETIME_MS = 5 * 60_000L
    private const val MAX_BODY = 200 * 1024 * 1024

    class Reply(val bytes: ByteArray, val type: String = "text/plain; charset=utf-8", val extra: String = "") {
        constructor(text: String, type: String = "text/plain; charset=utf-8") : this(text.toByteArray(), type)
    }

    /** A request: the path after the code, the method, headers (lower-case names) and the body. */
    class Request(val path: String, val method: String, val headers: Map<String, String>, val body: ByteArray)

    /** A small server for one transfer. [handle] runs on a background thread. */
    class Server(private val port: Int, val token: String, private val handle: (Request) -> Reply) {
        @Volatile private var socket: ServerSocket? = null
        val endsAt = SystemClock.elapsedRealtime() + LIFETIME_MS
        val running get() = socket != null && SystemClock.elapsedRealtime() < endsAt

        fun start(): Boolean {
            val s = runCatching { ServerSocket().apply { reuseAddress = true; soTimeout = 1000; bind(java.net.InetSocketAddress(port)) } }.getOrNull() ?: return false
            socket = s
            Thread({
                while (socket === s && SystemClock.elapsedRealtime() < endsAt) {
                    val c = try { s.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                    Thread({ runCatching { serve(c) }; runCatching { c.close() } }, "transfer-client").apply { isDaemon = true }.start()
                }
                runCatching { s.close() }
            }, "transfer").apply { isDaemon = true }.start()
            return true
        }

        fun stop() {
            val s = socket
            socket = null
            runCatching { s?.close() }
        }

        private fun serve(c: Socket) {
            c.soTimeout = 20_000
            // Home network only: never an address from outside.
            val from = c.inetAddress
            if (!(from.isSiteLocalAddress || from.isLoopbackAddress || from.isLinkLocalAddress)) return
            val input = c.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val first = lines.first().split(' ')
            val method = first.getOrNull(0) ?: return
            val path = first.getOrNull(1)?.substringBefore('?') ?: return
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
            val prefix = "/p/$token"
            val reply = if (path != prefix && !path.startsWith("$prefix/")) Reply(PhonePage.page("Not found",
                "<p>This address isn't open. On the TV, open the backup window again for a new code.</p>"), "text/html; charset=utf-8")
            else handle(Request(path.removePrefix(prefix).ifEmpty { "/" }, method, headers, body.copyOf(read)))
            c.getOutputStream().apply {
                write(("HTTP/1.0 200 OK\r\nContent-Type: ${reply.type}\r\nCache-Control: no-store\r\nContent-Length: ${reply.bytes.size}\r\n" +
                    reply.extra + "Connection: close\r\n\r\n").toByteArray())
                write(reply.bytes)
                flush()
            }
        }

        private fun readHead(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            var last4 = 0
            while (out.size() < 16_384) {
                val b = input.read()
                if (b < 0) return null
                out.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd()
            }
            return null
        }
    }

    // ---- TV to TV ----

    /** A 6-digit code for the receiving TV. */
    fun newTvCode(): String = (100000 + java.security.SecureRandom().nextInt(900000)).toString()

    /** The address part both TVs work out from the code (the code itself never travels). */
    fun tokenFor(code: String, kind: String = "b"): String = MessageDigest.getInstance("SHA-256").digest("xtv$kind:${code.filter { it.isDigit() }}".toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    /** This TV's name, for "Found …" on the other TV. */
    fun deviceName(context: Context): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL

    /**
     * The sending TV: finds the TV showing [code] on the home Wi-Fi (asks every address next to its own),
     * returns its address and name, or null.
     */
    fun find(code: String, kind: String = "b"): Pair<String, String>? {
        val own = PhonePage.localAddress() ?: return null
        val token = tokenFor(code, kind)
        val base = own.substringBeforeLast('.')
        val hosts = (1..254).map { "$base.$it" }.filter { it != own }.toMutableList()
        // The emulator reaches the computer (and a second emulator forwarded through it) here.
        if (BuildConfig.DEBUG) hosts.add(0, "10.0.2.2")
        val found = AtomicReference<Pair<String, String>?>(null)
        val pool = Executors.newFixedThreadPool(48)
        for (h in hosts) pool.execute {
            if (found.get() != null) return@execute
            runCatching {
                val c = URL("http://$h:$TV_PORT/p/$token/hello").openConnection() as HttpURLConnection
                c.connectTimeout = 700
                c.readTimeout = 1500
                val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (text.startsWith("xtv-receive:")) found.compareAndSet(null, h to text.removePrefix("xtv-receive:").take(40))
            }
        }
        pool.shutdown()
        pool.awaitTermination(12, TimeUnit.SECONDS)
        pool.shutdownNow()
        return found.get()
    }

    /** Sends the backup (already locked with the code) to the TV at [host]. Returns its answer. */
    fun send(host: String, code: String, sealed: ByteArray, kind: String = "b"): String {
        val c = URL("http://$host:$TV_PORT/p/${tokenFor(code, kind)}/send").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3000
        c.readTimeout = 30_000
        c.setFixedLengthStreamingMode(sealed.size)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { it.write(sealed) }
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
