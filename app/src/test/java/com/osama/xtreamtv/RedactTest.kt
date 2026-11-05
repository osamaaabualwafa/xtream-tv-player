package com.osama.xtreamtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Made-up account: none of these are real. */
class RedactTest {
    private val user = "demoUser42"
    private val pass = "s3cretPass!"
    private val server = "http://iptv.example-provider.com:8080"
    private val secrets = listOf(user, pass) + Redact.serverParts(server)

    private fun assertClean(line: String) {
        val out = Redact.clean(line, secrets)
        for (bad in listOf(user, pass, "example-provider", "8080", "http", "192.168", "10.0.0")) {
            assertFalse("\"$bad\" left in: $out", out.contains(bad, ignoreCase = true))
        }
    }

    @Test fun streamAddress() = assertClean("error playing $server/movie/$user/$pass/123.mkv: 403")

    @Test fun apiAddress() = assertClean("GET $server/player_api.php?username=$user&password=$pass&action=get_vod_streams")

    @Test fun bareHostAndIp() = assertClean("Failed to connect to iptv.example-provider.com/192.168.1.20:8080 after 15000ms")

    @Test fun resolveHost() = assertClean("Unable to resolve host \"iptv.example-provider.com\": No address associated")

    @Test fun loginInText() = assertClean("account demoUser42 refused, password S3CRETPASS! wrong")

    @Test fun lanAddress() = assertClean("share at 10.0.0.7:8095")

    @Test fun keepsUsefulText() {
        val line = "seek Left -5 s · asked 50:20 · landed 50:20 · java.net.SocketTimeoutException · 1.2.0 build 30"
        assertEquals(line, Redact.clean(line, secrets))
    }
}
