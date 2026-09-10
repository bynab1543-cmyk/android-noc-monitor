package com.noc.monitor.protocol.routeros

import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.PortPolicy
import com.noc.monitor.protocol.mapThrowable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Blocking RouterOS API / API-SSL session.
 *
 * Speaks the real binary API. Passwords are held as CharArray and never logged.
 */
class RouterOsApiClient(
    private val host: String,
    private val port: Int,
    private val useTls: Boolean,
    private val allowInsecureTls: Boolean,
    private val timeoutMs: Int = 8_000,
) : AutoCloseable {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var loggedIn = false

    @Synchronized
    fun connect() {
        PortPolicy.requireAllowed(port)
        closeQuietly()
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.soTimeout = timeoutMs
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            val connected: Socket = if (useTls) {
                wrapTls(plain)
            } else {
                plain
            }
            connected.soTimeout = timeoutMs
            socket = connected
            input = BufferedInputStream(connected.getInputStream())
            output = BufferedOutputStream(connected.getOutputStream())
        } catch (t: Throwable) {
            closeQuietly()
            throw mapIo(t)
        }
    }

    @Synchronized
    fun login(username: String, password: CharArray) {
        if (socket == null) connect()
        val pwd = String(password)
        try {
            val modern = talk(listOf("/login", RouterOsCodec.attr("name", username), RouterOsCodec.attr("password", pwd)))
            val trap = modern.firstOrNull { it.tag == "!trap" || it.tag == "!fatal" }
            if (trap != null) {
                val msg = trap.attrs["message"] ?: "login failed"
                if (looksLikeOldLogin(msg) || modern.none { it.tag == "!done" }) {
                    loginLegacy(username, pwd)
                } else {
                    throw authOrPermission(msg)
                }
            } else if (modern.any { it.tag == "!done" }) {
                val ret = modern.firstOrNull { it.tag == "!done" }?.attrs?.get("ret")
                if (!ret.isNullOrBlank() && modern.none { it.attrs.containsKey("name") }) {
                    // Some 6.43- devices still return a challenge in !done
                    loginLegacy(username, pwd, ret)
                } else {
                    loggedIn = true
                }
            } else {
                loginLegacy(username, pwd)
            }
        } finally {
            pwd.replace(Regex("."), "x")
        }
    }

    private fun looksLikeOldLogin(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("unknown parameter") || m.contains("invalid command") || m.contains("bad command")
    }

    private fun loginLegacy(username: String, password: String, existingChallenge: String? = null) {
        val challengeHex = existingChallenge ?: run {
            val first = talk(listOf("/login"))
            first.firstOrNull { it.tag == "!done" }?.attrs?.get("ret")
                ?: throw NocException(NocError.Protocol("Legacy login did not return a challenge"))
        }
        val response = "00" + md5Challenge(password, challengeHex)
        val second = talk(
            listOf(
                "/login",
                RouterOsCodec.attr("name", username),
                RouterOsCodec.attr("response", response),
            ),
        )
        val trap = second.firstOrNull { it.tag == "!trap" || it.tag == "!fatal" }
        if (trap != null) {
            throw authOrPermission(trap.attrs["message"] ?: "login failed")
        }
        if (second.none { it.tag == "!done" }) {
            throw NocException(NocError.AuthenticationFailed("Legacy login was not acknowledged"))
        }
        loggedIn = true
    }

    @Synchronized
    fun talk(words: List<String>): List<Sentence> {
        val out = output ?: throw NocException(NocError.Unreachable(host, "Not connected"))
        val inp = input ?: throw NocException(NocError.Unreachable(host, "Not connected"))
        try {
            RouterOsCodec.writeSentence(out, words)
            val replies = ArrayList<Sentence>(4)
            while (true) {
                val raw = RouterOsCodec.readSentence(inp)
                if (raw.isEmpty()) continue
                val (tag, attrs) = RouterOsCodec.parseAttributes(raw)
                val sentence = Sentence(tag, attrs, raw)
                replies.add(sentence)
                when (tag) {
                    "!done", "!fatal" -> return replies
                }
            }
        } catch (e: EOFException) {
            closeQuietly()
            throw NocException(NocError.Unreachable(host, "Connection closed by remote"), e)
        } catch (e: NocException) {
            throw e
        } catch (t: Throwable) {
            throw mapIo(t)
        }
    }

    fun print(command: String, extra: List<String> = emptyList()): List<Map<String, String>> {
        val words = ArrayList<String>(extra.size + 1)
        words.add(command)
        words.addAll(extra)
        val replies = talk(words)
        val trap = replies.firstOrNull { it.tag == "!trap" || it.tag == "!fatal" }
        if (trap != null) {
            throw trapToException(command, trap.attrs["message"] ?: trap.raw.joinToString(" "))
        }
        return replies.filter { it.tag == "!re" }.map { it.attrs }
    }

    fun command(command: String, extra: List<String> = emptyList()): List<Sentence> {
        val words = ArrayList<String>(extra.size + 1)
        words.add(command)
        words.addAll(extra)
        val replies = talk(words)
        val trap = replies.firstOrNull { it.tag == "!trap" || it.tag == "!fatal" }
        if (trap != null) {
            throw trapToException(command, trap.attrs["message"] ?: trap.raw.joinToString(" "))
        }
        return replies
    }

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        loggedIn = false
        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            output?.close()
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        input = null
        output = null
        socket = null
    }

    private fun wrapTls(plain: Socket): SSLSocket {
        val factory: SSLSocketFactory = if (allowInsecureTls) {
            insecureFactory()
        } else {
            SSLSocketFactory.getDefault() as SSLSocketFactory
        }
        val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return ssl
    }

    private fun insecureFactory(): SSLSocketFactory {
        val trust = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trust, SecureRandom())
        return ctx.socketFactory
    }

    private fun md5Challenge(password: String, challengeHex: String): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(0)
        md.update(password.toByteArray(Charsets.UTF_8))
        md.update(hexToBytes(challengeHex))
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            out[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private fun mapIo(t: Throwable): NocException {
        val mapped = when (t) {
            is java.net.SocketTimeoutException -> NocError.Timeout(host, port)
            is java.net.ConnectException -> NocError.ConnectionRefused(host, port)
            is java.net.UnknownHostException -> NocError.UnknownHost(host)
            is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLException ->
                NocError.TlsError(t.message ?: "TLS handshake failed")
            is NocException -> t.error
            else -> mapThrowable(t)
        }
        return if (t is NocException) t else NocException(mapped, t)
    }

    private fun authOrPermission(msg: String): NocException {
        val m = msg.lowercase()
        return if (m.contains("permission") || m.contains("not allowed") || m.contains("cannot")) {
            NocException(NocError.PermissionDenied(msg))
        } else {
            NocException(NocError.AuthenticationFailed(msg))
        }
    }

    private fun trapToException(command: String, msg: String): NocException {
        val m = msg.lowercase()
        return when {
            m.contains("invalid user") || m.contains("password") || m.contains("login") ->
                NocException(NocError.AuthenticationFailed(msg))
            m.contains("permission") || m.contains("not allowed") || m.contains("no such command") ->
                NocException(NocError.PermissionDenied(msg))
            else -> NocException(NocError.OperationFailed(command, msg))
        }
    }

    data class Sentence(
        val tag: String,
        val attrs: Map<String, String>,
        val raw: List<String>,
    )

    companion object {
        fun <T> runCatching(block: () -> T): NocResult<T> = NocResult.catch { block() }
    }
}
