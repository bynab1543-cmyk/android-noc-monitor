package com.noc.monitor.protocol.ubiquiti

import com.noc.monitor.protocol.ArpEntry
import com.noc.monitor.protocol.CommandOutcome
import com.noc.monitor.protocol.DeviceCapabilities
import com.noc.monitor.protocol.DeviceConnectionConfig
import com.noc.monitor.protocol.DeviceProvider
import com.noc.monitor.protocol.DhcpLease
import com.noc.monitor.protocol.IpAddressEntry
import com.noc.monitor.protocol.LogEntry
import com.noc.monitor.protocol.NetInterface
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.PppoeSession
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.RouteEntry
import com.noc.monitor.protocol.SystemInfo
import com.noc.monitor.protocol.TrafficCounters
import com.noc.monitor.protocol.unsupported
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Ubiquiti devices do not share one API. Each family has its own adapter and
 * only advertises commands that family actually supports.
 */
class UbiquitiProvider(
    private val config: DeviceConnectionConfig,
    private val passwordProvider: () -> CharArray,
) : DeviceProvider {
    private val adapter: UbiquitiAdapter = when (config.productFamily) {
        ProductFamily.UBIQUITI_UNIFI -> UnifiNetworkAdapter(config, passwordProvider)
        ProductFamily.UBIQUITI_EDGEOS -> EdgeOsAdapter(config, passwordProvider)
        ProductFamily.UBIQUITI_AIROS -> AirOsAdapter(config, passwordProvider)
        else -> throw NocException(NocError.Unsupported("provider", config.productFamily.name))
    }

    override val capabilities: DeviceCapabilities get() = adapter.capabilities

    override suspend fun testConnection() = adapter.testConnection()
    override suspend fun readSystem() = adapter.readSystem()
    override suspend fun readInterfaces() = adapter.readInterfaces()
    override suspend fun readTraffic() = adapter.readTraffic()
    override suspend fun readPppoe() = adapter.readPppoe()
    override suspend fun disconnectPppoe(sessionId: String) = adapter.disconnectPppoe(sessionId)
    override suspend fun setInterfaceEnabled(interfaceId: String, enabled: Boolean) =
        adapter.setInterfaceEnabled(interfaceId, enabled)
    override suspend fun reboot() = adapter.reboot()
    override suspend fun readIpAddresses() = adapter.readIpAddresses()
    override suspend fun readDhcpLeases() = adapter.readDhcpLeases()
    override suspend fun readArp() = adapter.readArp()
    override suspend fun readRoutes() = adapter.readRoutes()
    override suspend fun readLogs(limit: Int) = adapter.readLogs(limit)
    override fun close() = adapter.close()
}

internal interface UbiquitiAdapter : DeviceProvider

internal abstract class HttpAdapter(
    protected val config: DeviceConnectionConfig,
) {
    private val cookieJar = MemoryCookieJar()
    protected val client: OkHttpClient = buildClient()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .cookieJar(cookieJar)
        if (config.allowInsecureTls) {
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

    protected fun url(path: String): String {
        val scheme = "https"
        val p = if (path.startsWith("/")) path else "/$path"
        return "$scheme://${config.host}:${config.port}$p"
    }

    protected fun call(request: Request): Pair<Int, String> {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) {
                    throw NocException(NocError.AuthenticationFailed(extractMessage(body) ?: "HTTP 401"))
                }
                if (response.code == 403) {
                    throw NocException(NocError.PermissionDenied(extractMessage(body) ?: "HTTP 403"))
                }
                if (!response.isSuccessful) {
                    throw NocException(NocError.Http(response.code, extractMessage(body) ?: body.take(300)))
                }
                return response.code to body
            }
        } catch (e: NocException) {
            throw e
        } catch (t: Throwable) {
            throw when (t) {
                is java.net.SocketTimeoutException -> NocException(NocError.Timeout(config.host, config.port), t)
                is java.net.ConnectException -> NocException(NocError.ConnectionRefused(config.host, config.port), t)
                is java.net.UnknownHostException -> NocException(NocError.UnknownHost(config.host), t)
                is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLException ->
                    NocException(NocError.TlsError(t.message ?: "TLS handshake failed"), t)
                else -> NocException(com.noc.monitor.protocol.mapThrowable(t), t)
            }
        }
    }

    private fun extractMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = if (body.trim().startsWith("[")) JSONArray(body).optJSONObject(0) else JSONObject(body)
            obj?.optString("meta")?.ifBlank { null }
                ?: obj?.optJSONObject("meta")?.optString("msg")?.ifBlank { null }
                ?: obj?.optString("message")?.ifBlank { null }
                ?: obj?.optString("error")?.ifBlank { null }
        } catch (_: Throwable) {
            body.take(200)
        }
    }

    fun closeHttp() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

private class MemoryCookieJar : CookieJar {
    private val store = CopyOnWriteArrayList<Cookie>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.removeAll { c -> cookies.any { it.name == c.name } }
        store.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.filter { it.matches(url) }
}

/**
 * UniFi Network controller / UniFi OS (UDM) HTTPS JSON API.
 * PPPoE session disconnect is not a UniFi station operation — left unsupported.
 */
internal class UnifiNetworkAdapter(
    config: DeviceConnectionConfig,
    private val passwordProvider: () -> CharArray,
) : HttpAdapter(config), UbiquitiAdapter {
    override val capabilities: DeviceCapabilities = DeviceCapabilities.uniFi()
    private var loggedIn = false
    private var site = "default"
    private var uniFiOs = false

    override suspend fun testConnection(): NocResult<SystemInfo> = NocResult.catch { loginAndSystem() }
    override suspend fun readSystem(): NocResult<SystemInfo> = NocResult.catch { loginAndSystem() }

    override suspend fun readInterfaces(): NocResult<List<NetInterface>> = NocResult.catch {
        ensureLogin()
        val devices = getJson(apiPath("/stat/device"))
        val list = ArrayList<NetInterface>()
        val data = devices.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val d = data.getJSONObject(i)
            val ports = d.optJSONArray("port_table") ?: continue
            for (p in 0 until ports.length()) {
                val port = ports.getJSONObject(p)
                val name = port.optString("name").ifBlank { "port${port.optInt("port_idx")}" }
                list += NetInterface(
                    id = d.optString("mac") + ":" + name,
                    name = (d.optString("name").ifBlank { d.optString("mac") }) + "/" + name,
                    type = port.optString("media").ifBlank { "ethernet" },
                    running = port.optBoolean("up", false),
                    enabled = port.optBoolean("enable", true),
                    mac = d.optString("mac"),
                    rxBytes = port.optLong("rx_bytes"),
                    txBytes = port.optLong("tx_bytes"),
                    rxPackets = port.optLong("rx_packets"),
                    txPackets = port.optLong("tx_packets"),
                )
            }
        }
        list
    }

    override suspend fun readTraffic(): NocResult<List<TrafficCounters>> {
        return readInterfaces().map { ifaces ->
            ifaces.map {
                TrafficCounters(it.name, it.rxBytes, it.txBytes, it.rxPackets, it.txPackets)
            }
        }
    }

    override suspend fun readPppoe() = unsupported("PPPoE sessions", ProductFamily.UBIQUITI_UNIFI)
    override suspend fun disconnectPppoe(sessionId: String) = unsupported("Disconnect PPPoE", ProductFamily.UBIQUITI_UNIFI)
    override suspend fun setInterfaceEnabled(interfaceId: String, enabled: Boolean) =
        unsupported("Enable/disable interface", ProductFamily.UBIQUITI_UNIFI)

    override suspend fun reboot(): NocResult<CommandOutcome> = NocResult.catch {
        ensureLogin()
        throw NocException(
            NocError.Unsupported(
                "Reboot requires a specific UniFi device MAC and is not exposed as a site-wide command here",
                ProductFamily.UBIQUITI_UNIFI.name,
            ),
        )
    }

    override suspend fun readIpAddresses(): NocResult<List<IpAddressEntry>> = NocResult.catch {
        ensureLogin()
        val devices = getJson(apiPath("/stat/device"))
        val data = devices.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<IpAddressEntry>()
        for (i in 0 until data.length()) {
            val d = data.getJSONObject(i)
            val ip = d.optString("ip")
            if (ip.isNotBlank()) {
                out += IpAddressEntry(
                    id = d.optString("mac"),
                    address = ip,
                    interfaceName = d.optString("name"),
                    comment = d.optString("model"),
                )
            }
        }
        out
    }

    override suspend fun readDhcpLeases() = unsupported("DHCP leases", ProductFamily.UBIQUITI_UNIFI)
    override suspend fun readArp() = unsupported("ARP table", ProductFamily.UBIQUITI_UNIFI)
    override suspend fun readRoutes() = unsupported("Routes", ProductFamily.UBIQUITI_UNIFI)
    override suspend fun readLogs(limit: Int) = unsupported("Device logs", ProductFamily.UBIQUITI_UNIFI)
    override fun close() = closeHttp()

    private fun loginAndSystem(): SystemInfo {
        ensureLogin()
        val sys = try {
            getJson(if (uniFiOs) "/proxy/network/api/s/$site/stat/sysinfo" else "/api/s/$site/stat/sysinfo")
        } catch (_: Throwable) {
            getJson(apiPath("/stat/device"))
        }
        val data = sys.optJSONArray("data")?.optJSONObject(0) ?: JSONObject()
        return SystemInfo(
            identity = data.optString("name").ifBlank { config.displayName.ifBlank { config.host } },
            model = data.optString("ubnt_device_type").ifBlank { "UniFi" },
            version = data.optString("version").ifBlank { data.optString("firmware") },
            uptime = data.optLong("uptime").takeIf { it > 0 }?.let { "${it}s" },
            cpuLoadPercent = data.optJSONObject("system-stats")?.optString("cpu")?.toDoubleOrNull()?.toInt(),
            memoryTotalBytes = null,
            memoryFreeBytes = null,
            platform = "Ubiquiti UniFi",
        )
    }

    private fun ensureLogin() {
        if (loggedIn) return
        val password = String(passwordProvider())
        val json = JSONObject()
            .put("username", config.username)
            .put("password", password)
            .toString()
        val media = "application/json".toMediaType()
        // UniFi OS (UDM/UDR) first, then classic controller.
        val osReq = Request.Builder()
            .url(url("/api/auth/login"))
            .post(json.toRequestBody(media))
            .header("Content-Type", "application/json")
            .build()
        try {
            call(osReq)
            uniFiOs = true
            loggedIn = true
            return
        } catch (e: NocException) {
            if (e.error is NocError.AuthenticationFailed) throw e
        }
        val classic = Request.Builder()
            .url(url("/api/login"))
            .post(json.toRequestBody(media))
            .header("Content-Type", "application/json")
            .build()
        call(classic)
        uniFiOs = false
        loggedIn = true
    }

    private fun apiPath(suffix: String): String {
        return if (uniFiOs) "/proxy/network/api/s/$site$suffix" else "/api/s/$site$suffix"
    }

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder().url(url(path)).get().build()
        val (_, body) = call(req)
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}

/**
 * EdgeOS / EdgeRouter session + REST (`/api/` and operational JSON).
 */
internal class EdgeOsAdapter(
    config: DeviceConnectionConfig,
    private val passwordProvider: () -> CharArray,
) : HttpAdapter(config), UbiquitiAdapter {
    override val capabilities: DeviceCapabilities = DeviceCapabilities.edgeOs()
    private var loggedIn = false

    override suspend fun testConnection(): NocResult<SystemInfo> = NocResult.catch { loginAndSystem() }
    override suspend fun readSystem(): NocResult<SystemInfo> = NocResult.catch { loginAndSystem() }

    override suspend fun readInterfaces(): NocResult<List<NetInterface>> = NocResult.catch {
        ensureLogin()
        val data = getJson("/api/edge/data.json?type=interfaces")
        val out = ArrayList<NetInterface>()
        val output = data.optJSONObject("output") ?: data
        val keys = output.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val row = output.optJSONObject(name) ?: continue
            val stats = row.optJSONObject("stats") ?: JSONObject()
            out += NetInterface(
                id = name,
                name = name,
                type = row.optString("type").ifBlank { "ethernet" },
                running = row.optString("l1up") == "up" || row.optBoolean("up"),
                enabled = !row.optBoolean("disabled"),
                mac = row.optString("mac"),
                rxBytes = stats.optLong("rx_bytes"),
                txBytes = stats.optLong("tx_bytes"),
                rxPackets = stats.optLong("rx_packets"),
                txPackets = stats.optLong("tx_packets"),
            )
        }
        out
    }

    override suspend fun readTraffic(): NocResult<List<TrafficCounters>> =
        readInterfaces().map { ifaces -> ifaces.map { TrafficCounters(it.name, it.rxBytes, it.txBytes, it.rxPackets, it.txPackets) } }

    override suspend fun readPppoe(): NocResult<List<PppoeSession>> = NocResult.catch {
        ensureLogin()
        val data = try {
            getJson("/api/edge/data.json?type=pppoe")
        } catch (_: Throwable) {
            return@catch emptyList()
        }
        val out = ArrayList<PppoeSession>()
        val arr = data.optJSONArray("output") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            out += PppoeSession(
                id = row.optString("interface").ifBlank { row.optString("user") },
                name = row.optString("user").ifBlank { row.optString("interface") },
                address = row.optString("ip"),
                uptime = row.optString("uptime"),
            )
        }
        out
    }

    override suspend fun disconnectPppoe(sessionId: String) = unsupported("Disconnect PPPoE", ProductFamily.UBIQUITI_EDGEOS)
    override suspend fun setInterfaceEnabled(interfaceId: String, enabled: Boolean) =
        unsupported("Enable/disable interface", ProductFamily.UBIQUITI_EDGEOS)

    override suspend fun reboot(): NocResult<CommandOutcome> = NocResult.catch {
        ensureLogin()
        val body = FormBody.Builder().add("action", "reboot").build()
        val req = Request.Builder().url(url("/api/edge/operation.json")).post(body).build()
        call(req)
        CommandOutcome(true, "EdgeOS reboot", "Reboot command sent")
    }

    override suspend fun readIpAddresses(): NocResult<List<IpAddressEntry>> = NocResult.catchSuspend {
        readInterfaces().getOrNull()?.map {
            IpAddressEntry(id = it.id, address = it.name, interfaceName = it.name)
        } ?: emptyList()
    }

    override suspend fun readDhcpLeases(): NocResult<List<DhcpLease>> = NocResult.catch {
        ensureLogin()
        val data = getJson("/api/edge/data.json?type=dhcp_leases")
        val out = ArrayList<DhcpLease>()
        val output = data.optJSONObject("output") ?: JSONObject()
        val dhcpd = output.optJSONObject("dhcpd") ?: output
        val keys = dhcpd.keys()
        while (keys.hasNext()) {
            val pool = keys.next()
            val leases = dhcpd.optJSONObject(pool) ?: continue
            val lkeys = leases.keys()
            while (lkeys.hasNext()) {
                val ip = lkeys.next()
                val row = leases.optJSONObject(ip) ?: continue
                out += DhcpLease(
                    id = ip,
                    address = ip,
                    mac = row.optString("mac"),
                    hostName = row.optString("hostname"),
                    status = row.optString("pool"),
                )
            }
        }
        out
    }

    override suspend fun readArp(): NocResult<List<ArpEntry>> = NocResult.catch {
        ensureLogin()
        val data = try {
            getJson("/api/edge/data.json?type=arp")
        } catch (_: Throwable) {
            return@catch emptyList()
        }
        emptyList()
    }

    override suspend fun readRoutes(): NocResult<List<RouteEntry>> = NocResult.catch {
        ensureLogin()
        val data = try {
            getJson("/api/edge/data.json?type=routes")
        } catch (_: Throwable) {
            return@catch emptyList()
        }
        emptyList()
    }

    override suspend fun readLogs(limit: Int) = unsupported("Device logs", ProductFamily.UBIQUITI_EDGEOS)
    override fun close() = closeHttp()

    private fun loginAndSystem(): SystemInfo {
        ensureLogin()
        val data = try {
            getJson("/api/edge/data.json?type=system")
        } catch (_: Throwable) {
            JSONObject()
        }
        val output = data.optJSONObject("output") ?: data
        return SystemInfo(
            identity = output.optString("host-name").ifBlank { config.displayName.ifBlank { config.host } },
            model = output.optString("product").ifBlank { "EdgeRouter" },
            version = output.optString("version"),
            uptime = output.optString("uptime"),
            platform = "Ubiquiti EdgeOS",
        )
    }

    private fun ensureLogin() {
        if (loggedIn) return
        val password = String(passwordProvider())
        val body = FormBody.Builder()
            .add("username", config.username)
            .add("password", password)
            .build()
        val req = Request.Builder().url(url("/")).post(body).build()
        call(req)
        loggedIn = true
    }

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder().url(url(path)).get().build()
        val (_, body) = call(req)
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}

/**
 * airOS (airMAX) CGI / status JSON. Feature set is small by design.
 */
internal class AirOsAdapter(
    config: DeviceConnectionConfig,
    private val passwordProvider: () -> CharArray,
) : HttpAdapter(config), UbiquitiAdapter {
    override val capabilities: DeviceCapabilities = DeviceCapabilities.airOs()
    private var loggedIn = false

    override suspend fun testConnection(): NocResult<SystemInfo> = NocResult.catch { loginAndSystem() }
    override suspend fun readSystem(): NocResult<SystemInfo> = NocResult.catch { loginAndSystem() }

    override suspend fun readInterfaces(): NocResult<List<NetInterface>> = NocResult.catch {
        val status = statusJson()
        val ifaces = status.optJSONObject("ifaces") ?: JSONObject()
        val out = ArrayList<NetInterface>()
        val keys = ifaces.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val row = ifaces.optJSONObject(name) ?: continue
            val stats = row.optJSONObject("stats") ?: JSONObject()
            out += NetInterface(
                id = name,
                name = name,
                type = row.optString("ifname").ifBlank { name },
                running = row.optBoolean("status", true),
                enabled = true,
                mac = row.optString("mac"),
                rxBytes = stats.optLong("rx_bytes"),
                txBytes = stats.optLong("tx_bytes"),
                rxPackets = stats.optLong("rx_packets"),
                txPackets = stats.optLong("tx_packets"),
            )
        }
        out
    }

    override suspend fun readTraffic(): NocResult<List<TrafficCounters>> =
        readInterfaces().map { ifaces -> ifaces.map { TrafficCounters(it.name, it.rxBytes, it.txBytes, it.rxPackets, it.txPackets) } }

    override suspend fun readPppoe() = unsupported("PPPoE sessions", ProductFamily.UBIQUITI_AIROS)
    override suspend fun disconnectPppoe(sessionId: String) = unsupported("Disconnect PPPoE", ProductFamily.UBIQUITI_AIROS)
    override suspend fun setInterfaceEnabled(interfaceId: String, enabled: Boolean) =
        unsupported("Enable/disable interface", ProductFamily.UBIQUITI_AIROS)

    override suspend fun reboot(): NocResult<CommandOutcome> = NocResult.catch {
        ensureLogin()
        val req = Request.Builder()
            .url(url("/api/reboot"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        try {
            call(req)
        } catch (e: NocException) {
            if (e.error is NocError.Http && (e.error as NocError.Http).code == 404) {
                throw NocException(NocError.Unsupported("Reboot", ProductFamily.UBIQUITI_AIROS.name))
            }
            throw e
        }
        CommandOutcome(true, "airOS reboot", "Reboot command sent")
    }

    override suspend fun readIpAddresses(): NocResult<List<IpAddressEntry>> = NocResult.catch {
        val status = statusJson()
        val wan = status.optJSONObject("wan") ?: JSONObject()
        val ip = wan.optString("ip")
        if (ip.isBlank()) emptyList()
        else listOf(IpAddressEntry(id = "wan", address = ip, interfaceName = "wan"))
    }

    override suspend fun readDhcpLeases() = unsupported("DHCP leases", ProductFamily.UBIQUITI_AIROS)
    override suspend fun readArp() = unsupported("ARP table", ProductFamily.UBIQUITI_AIROS)
    override suspend fun readRoutes() = unsupported("Routes", ProductFamily.UBIQUITI_AIROS)
    override suspend fun readLogs(limit: Int) = unsupported("Device logs", ProductFamily.UBIQUITI_AIROS)
    override fun close() = closeHttp()

    private fun loginAndSystem(): SystemInfo {
        val status = statusJson()
        val host = status.optJSONObject("host") ?: status
        return SystemInfo(
            identity = host.optString("hostname").ifBlank { config.displayName.ifBlank { config.host } },
            model = host.optString("devmodel").ifBlank { "airOS" },
            version = host.optString("fwversion"),
            uptime = host.opt("uptime")?.toString(),
            cpuLoadPercent = host.optDouble("cpuload", Double.NaN).takeIf { !it.isNaN() }?.toInt(),
            platform = "Ubiquiti airOS",
        )
    }

    private fun ensureLogin() {
        if (loggedIn) return
        val password = String(passwordProvider())
        val body = FormBody.Builder()
            .add("username", config.username)
            .add("password", password)
            .build()
        val req = Request.Builder().url(url("/api/auth")).post(body).build()
        try {
            call(req)
            loggedIn = true
            return
        } catch (e: NocException) {
            if (e.error is NocError.AuthenticationFailed) throw e
        }
        val basic = Request.Builder()
            .url(url("/status.cgi"))
            .header("Authorization", Credentials.basic(config.username, password))
            .get()
            .build()
        call(basic)
        loggedIn = true
    }

    private fun statusJson(): JSONObject {
        ensureLogin()
        val req = Request.Builder().url(url("/status.cgi")).get().build()
        val (_, body) = call(req)
        return JSONObject(body)
    }
}
