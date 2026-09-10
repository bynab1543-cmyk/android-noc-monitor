package com.noc.monitor.protocol

/**
 * Port 9 is the discard / WOL magic-packet port and must never be used
 * as a management API port in this application.
 */
object PortPolicy {
    const val FORBIDDEN_PORT = 9
    const val DEFAULT_API = 8728
    const val DEFAULT_API_SSL = 8729
    const val DEFAULT_REST = 443
    const val DEFAULT_UNIFI = 443
    const val DEFAULT_EDGEOS = 443
    const val DEFAULT_AIROS = 443

    fun isForbidden(port: Int): Boolean = port == FORBIDDEN_PORT

    fun requireAllowed(port: Int) {
        if (port !in 1..65535) {
            throw NocException(NocError.InvalidPort(port, "Port must be between 1 and 65535"))
        }
        if (isForbidden(port)) {
            throw NocException(
                NocError.PortNotAllowed(
                    port,
                    "Port 9 is not allowed. Use the device management API port (MikroTik API 8728, API-SSL 8729, REST 443).",
                ),
            )
        }
    }

    fun defaultPort(transport: Transport): Int = when (transport) {
        Transport.API -> DEFAULT_API
        Transport.API_SSL -> DEFAULT_API_SSL
        Transport.REST -> DEFAULT_REST
        Transport.UNIFI -> DEFAULT_UNIFI
        Transport.EDGEOS -> DEFAULT_EDGEOS
        Transport.AIROS -> DEFAULT_AIROS
    }
}

enum class Transport {
    API,
    API_SSL,
    REST,
    UNIFI,
    EDGEOS,
    AIROS,
}

enum class Vendor {
    MIKROTIK,
    UBIQUITI,
}

enum class ProductFamily {
    MIKROTIK_ROUTEROS,
    UBIQUITI_UNIFI,
    UBIQUITI_EDGEOS,
    UBIQUITI_AIROS,
}

enum class OperatingMode {
    REAL,
    DEMO,
}

data class DeviceConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val transport: Transport,
    val vendor: Vendor,
    val productFamily: ProductFamily,
    val allowInsecureTls: Boolean = false,
    val timeoutMs: Int = 8_000,
    val displayName: String = "",
) {
    init {
        PortPolicy.requireAllowed(port)
        require(host.isNotBlank()) { "Host is required" }
        require(username.isNotBlank()) { "Username is required" }
    }
}

sealed class NocError {
    abstract val userMessage: String

    data class Timeout(val host: String, val port: Int) : NocError() {
        override val userMessage: String = "Connection timed out contacting $host:$port"
    }

    data class ConnectionRefused(val host: String, val port: Int) : NocError() {
        override val userMessage: String = "Connection refused by $host:$port"
    }

    data class UnknownHost(val host: String) : NocError() {
        override val userMessage: String = "Unknown host: $host"
    }

    data class AuthenticationFailed(val detail: String) : NocError() {
        override val userMessage: String = "Authentication failed: $detail"
    }

    data class TlsError(val detail: String) : NocError() {
        override val userMessage: String = "TLS error: $detail"
    }

    data class PermissionDenied(val detail: String) : NocError() {
        override val userMessage: String = "Permission denied: $detail"
    }

    data class Unsupported(val operation: String, val family: String) : NocError() {
        override val userMessage: String = "Unsupported on $family: $operation"
    }

    data class Protocol(val detail: String) : NocError() {
        override val userMessage: String = "Protocol error: $detail"
    }

    data class PortNotAllowed(val port: Int, val detail: String) : NocError() {
        override val userMessage: String = detail
    }

    data class InvalidPort(val port: Int, val detail: String) : NocError() {
        override val userMessage: String = detail
    }

    data class OperationFailed(val command: String, val detail: String) : NocError() {
        override val userMessage: String = "$command failed: $detail"
    }

    data class Unreachable(val host: String, val detail: String) : NocError() {
        override val userMessage: String = "Unreachable $host: $detail"
    }

    data class Http(val code: Int, val detail: String) : NocError() {
        override val userMessage: String = "HTTP $code: $detail"
    }

    data class Crypto(val detail: String) : NocError() {
        override val userMessage: String = "Secure storage error: $detail"
    }

    data class DemoBlocked(val detail: String) : NocError() {
        override val userMessage: String = detail
    }
}

class NocException(val error: NocError, cause: Throwable? = null) : Exception(error.userMessage, cause)

sealed class NocResult<out T> {
    data class Ok<T>(val value: T) : NocResult<T>()
    data class Err(val error: NocError) : NocResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): NocError? = (this as? Err)?.error

    inline fun <R> map(transform: (T) -> R): NocResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    companion object {
        fun <T> ok(value: T): NocResult<T> = Ok(value)
        fun <T> err(error: NocError): NocResult<T> = Err(error)
        fun <T> catch(errorMapper: (Throwable) -> NocError = ::mapThrowable, block: () -> T): NocResult<T> {
            return try {
                Ok(block())
            } catch (e: NocException) {
                Err(e.error)
            } catch (t: Throwable) {
                Err(errorMapper(t))
            }
        }

        suspend fun <T> catchSuspend(
            errorMapper: (Throwable) -> NocError = ::mapThrowable,
            block: suspend () -> T,
        ): NocResult<T> {
            return try {
                Ok(block())
            } catch (e: NocException) {
                Err(e.error)
            } catch (t: Throwable) {
                Err(errorMapper(t))
            }
        }
    }
}

fun mapThrowable(t: Throwable): NocError {
    var cur: Throwable? = t
    while (cur != null) {
        when (cur) {
            is NocException -> return cur.error
            is java.net.SocketTimeoutException -> {
                val host = (t as? java.net.ConnectException)?.message ?: "device"
                return NocError.Timeout(host, 0)
            }
            is java.net.ConnectException -> {
                val msg = cur.message ?: t.message ?: "connection refused"
                val hostPort = Regex("""([^:]+):(\d+)""").find(msg)
                val host = hostPort?.groupValues?.get(1) ?: "device"
                val port = hostPort?.groupValues?.get(2)?.toIntOrNull() ?: 0
                return NocError.ConnectionRefused(host, port)
            }
            is java.net.UnknownHostException -> return NocError.UnknownHost(cur.message ?: t.message ?: "unknown")
            is java.net.NoRouteToHostException -> return NocError.Unreachable(cur.message ?: "device", cur.message ?: "")
            is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLException ->
                return NocError.TlsError(cur.message ?: t.message ?: "TLS handshake failed")
            is java.io.InterruptedIOException -> return NocError.Timeout("device", 0)
        }
        cur = cur.cause
    }
    return NocError.Unreachable("device", t.message ?: t.javaClass.simpleName)
}

data class CommandOutcome(
    val success: Boolean,
    val command: String,
    val message: String,
    val deviceMessage: String? = null,
) {
    val display: String
        get() = if (deviceMessage.isNullOrBlank()) message else "$message ($deviceMessage)"
}
