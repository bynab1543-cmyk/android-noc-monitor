package com.noc.monitor.protocol.routeros

import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import com.noc.monitor.protocol.PortPolicy
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * RouterOS REST API client (RouterOS 7+).
 * Uses HTTP Basic over HTTPS (or HTTP when the operator explicitly selected REST on a non-TLS lab).
 */
class RouterOsRestClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    password: CharArray,
    private val useHttps: Boolean = true,
    private val allowInsecureTls: Boolean = false,
    private val timeoutMs: Int = 8_000,
) : AutoCloseable {
    private val passwordSnapshot = String(password)
    private val client: OkHttpClient = buildClient()

    init {
        PortPolicy.requireAllowed(port)
    }

    fun getObject(path: String): JSONObject {
        val body = execute("GET", path, null)
        return parseObject(body)
    }

    fun getArray(path: String): List<JSONObject> {
        val body = execute("GET", path, null)
        return parseArray(body)
    }

    fun post(path: String, json: String? = null): String {
        return execute("POST", path, json)
    }

    fun patch(path: String, json: String): String {
        return execute("PATCH", path, json)
    }

    fun delete(path: String): String {
        return execute("DELETE", path, null)
    }

    private fun execute(method: String, path: String, json: String?): String {
        val scheme = if (useHttps) "https" else "http"
        val normalized = if (path.startsWith("/")) path else "/$path"
        val restPath = if (normalized.startsWith("/rest")) normalized else "/rest$normalized"
        val url = "$scheme://$host:$port$restPath"
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(username, passwordSnapshot))
            .header("Accept", "application/json")
        val media = "application/json; charset=utf-8".toMediaType()
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((json ?: "{}").toRequestBody(media))
            "PATCH" -> builder.patch(json!!.toRequestBody(media))
            else -> throw NocException(NocError.Protocol("Unsupported HTTP method $method"))
        }
        val request = builder.build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    val msg = extractError(body) ?: response.message
                    if (response.code == 401) {
                        throw NocException(NocError.AuthenticationFailed(msg.ifBlank { "HTTP 401" }))
                    }
                    throw NocException(NocError.PermissionDenied(msg.ifBlank { "HTTP 403" }))
                }
                if (response.code == 404) {
                    throw NocException(NocError.OperationFailed(path, extractError(body) ?: "REST endpoint not found (RouterOS 7+ required)"))
                }
                if (!response.isSuccessful) {
                    throw NocException(
                        NocError.Http(response.code, extractError(body) ?: body.ifBlank { response.message }),
                    )
                }
                return body
            }
        } catch (e: NocException) {
            throw e
        } catch (t: Throwable) {
            throw when (t) {
                is java.net.SocketTimeoutException -> NocException(NocError.Timeout(host, port), t)
                is java.net.ConnectException -> NocException(NocError.ConnectionRefused(host, port), t)
                is java.net.UnknownHostException -> NocException(NocError.UnknownHost(host), t)
                is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLException ->
                    NocException(NocError.TlsError(t.message ?: "TLS handshake failed"), t)
                else -> NocException(com.noc.monitor.protocol.mapThrowable(t), t)
            }
        }
    }

    private fun parseObject(body: String): JSONObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return JSONObject()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            return if (arr.length() == 0) JSONObject() else arr.getJSONObject(0)
        }
        return JSONObject(trimmed)
    }

    private fun parseArray(body: String): List<JSONObject> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        return listOf(JSONObject(trimmed))
    }

    private fun extractError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = if (body.trim().startsWith("[")) {
                JSONArray(body).optJSONObject(0) ?: return body
            } else {
                JSONObject(body)
            }
            obj.optString("message").ifBlank { obj.optString("detail").ifBlank { obj.optString("error") } }
                .ifBlank { null }
        } catch (_: Throwable) {
            body.take(300)
        }
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((timeoutMs * 2).toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
        if (allowInsecureTls) {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<X509TrustManager>(trust), SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trust)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
