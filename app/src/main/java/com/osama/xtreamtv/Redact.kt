package com.osama.xtreamtv

/**
 * Cleans a line before it goes into the diagnostics report. Stream addresses hold the account's
 * username and password, so nothing that looks like an address, a server name or a saved login may
 * stay. Plain Kotlin (no Android), so it is tested on the computer: see RedactTest.
 */
object Redact {
    private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://\S+""")
    private val IP = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b""")
    private val IPV6 = Regex("""\[?\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){3,7}\b]?(?::\d{1,5})?""")
    private val HOST = Regex(
        """\b(?:[a-zA-Z0-9-]+\.)+(?:com|net|org|tv|io|xyz|me|cc|info|live|pro|club|top|online|site|vip|co|uk|de|fr|nl|ru|us|ca|eu|biz|stream|space|fun|world|store|ws|to|in|ae|sa|eg)(?::\d{1,5})?\b(?!\.[A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val NAMED_HOST = Regex("""(?i)(unable to resolve host|failed to connect to|connect to|host)\s+"?[^\s",;)]+"?""")
    private val QUERY = Regex("""(?i)(username|password|user|pass|token|key|apikey|api_key)=[^&\s]+""")

    /** [secrets]: saved usernames, passwords, server names and keys, replaced wherever they appear. */
    fun clean(text: String, secrets: Collection<String>): String {
        var s = URL.replace(text, "[address]")
        s = QUERY.replace(s) { "${it.groupValues[1]}=[hidden]" }
        // Longest first, so a password that contains a username is still hidden whole.
        for (secret in secrets.filter { it.length >= 3 }.sortedByDescending { it.length }) {
            s = s.replace(secret, "[hidden]", ignoreCase = true)
        }
        s = NAMED_HOST.replace(s) { "${it.groupValues[1]} [server]" }
        s = HOST.replace(s, "[server]")
        s = IP.replace(s, "[ip]")
        s = IPV6.replace(s, "[ip]")
        return s.replace('\n', ' ').replace('\t', ' ')
    }

    /** The parts of a saved server address worth hiding: "http://a.b.com:8080" gives "a.b.com:8080", "a.b.com". */
    fun serverParts(base: String): List<String> {
        val host = base.substringAfter("://").substringBefore('/')
        return listOf(host, host.substringBefore(':')).distinct()
    }
}
