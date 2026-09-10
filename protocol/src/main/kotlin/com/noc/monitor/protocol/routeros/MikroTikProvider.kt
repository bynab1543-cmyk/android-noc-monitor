package com.noc.monitor.protocol.routeros

import com.noc.monitor.protocol.ArpEntry
import com.noc.monitor.protocol.CommandOutcome
import com.noc.monitor.protocol.DeviceConnectionConfig
import com.noc.monitor.protocol.DhcpLease
import com.noc.monitor.protocol.IpAddressEntry
import com.noc.monitor.protocol.LogEntry
import com.noc.monitor.protocol.NetInterface
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.PppoeSession
import com.noc.monitor.protocol.RouteEntry
import com.noc.monitor.protocol.SystemInfo
import com.noc.monitor.protocol.TrafficCounters
import com.noc.monitor.protocol.Transport
import org.json.JSONObject

object RouterOsMapper {
    fun systemFromApi(identity: Map<String, String>, resource: Map<String, String>, health: Map<String, String>): SystemInfo {
        return SystemInfo(
            identity = identity["name"] ?: identity["identity"] ?: "MikroTik",
            model = resource["board-name"] ?: resource["platform"],
            version = resource["version"],
            uptime = resource["uptime"],
            cpuLoadPercent = resource["cpu-load"]?.toIntOrNull(),
            cpuCount = resource["cpu-count"]?.toIntOrNull(),
            architecture = resource["architecture-name"] ?: resource["cpu"],
            boardName = resource["board-name"],
            memoryTotalBytes = resource["total-memory"]?.toLongOrNull(),
            memoryFreeBytes = resource["free-memory"]?.toLongOrNull(),
            storageTotalBytes = resource["total-hdd-space"]?.toLongOrNull(),
            storageFreeBytes = resource["free-hdd-space"]?.toLongOrNull(),
            temperatureC = parseTemperature(health, resource),
            platform = resource["platform"] ?: "MikroTik",
        )
    }

    fun systemFromJson(identity: JSONObject, resource: JSONObject, health: JSONObject): SystemInfo {
        return systemFromApi(jsonToMap(identity), jsonToMap(resource), jsonToMap(health))
    }

    fun parseTemperature(health: Map<String, String>, resource: Map<String, String> = emptyMap()): Double? {
        val keys = listOf(
            "temperature",
            "cpu-temperature",
            "board-temperature1",
            "board-temperature",
            "switch-temperature",
        )
        for (k in keys) {
            val v = health[k] ?: resource[k] ?: continue
            val num = v.replace("C", "", ignoreCase = true).trim().toDoubleOrNull()
            if (num != null) return num
        }
        return null
    }

    fun interfaceFromApi(row: Map<String, String>): NetInterface {
        val disabled = truthy(row["disabled"])
        return NetInterface(
            id = row[".id"] ?: row["name"].orEmpty(),
            name = row["name"].orEmpty(),
            type = row["type"],
            running = truthy(row["running"]),
            enabled = !disabled,
            mac = row["mac-address"],
            comment = row["comment"],
            rxBytes = row["rx-byte"]?.toLongOrNull() ?: 0L,
            txBytes = row["tx-byte"]?.toLongOrNull() ?: 0L,
            rxPackets = row["rx-packet"]?.toLongOrNull() ?: 0L,
            txPackets = row["tx-packet"]?.toLongOrNull() ?: 0L,
            mtu = row["mtu"]?.toIntOrNull(),
        )
    }

    fun interfaceFromJson(obj: JSONObject): NetInterface = interfaceFromApi(jsonToMap(obj))

    fun pppoeFromApi(row: Map<String, String>): PppoeSession = PppoeSession(
        id = row[".id"] ?: row["name"].orEmpty(),
        name = row["name"].orEmpty(),
        service = row["service"],
        address = row["address"],
        callerId = row["caller-id"],
        uptime = row["uptime"],
        encoding = row["encoding"],
        caller = row["caller-id"],
    )

    fun pppoeFromJson(obj: JSONObject): PppoeSession = pppoeFromApi(jsonToMap(obj))

    fun ipFromApi(row: Map<String, String>): IpAddressEntry = IpAddressEntry(
        id = row[".id"] ?: row["address"].orEmpty(),
        address = row["address"].orEmpty(),
        interfaceName = row["interface"],
        network = row["network"],
        comment = row["comment"],
        disabled = truthy(row["disabled"]),
    )

    fun dhcpFromApi(row: Map<String, String>): DhcpLease = DhcpLease(
        id = row[".id"] ?: row["address"].orEmpty(),
        address = row["address"].orEmpty(),
        mac = row["mac-address"],
        hostName = row["host-name"],
        status = row["status"],
        server = row["server"],
        lastSeen = row["last-seen"],
        expiresAfter = row["expires-after"],
    )

    fun arpFromApi(row: Map<String, String>): ArpEntry = ArpEntry(
        id = row[".id"] ?: (row["address"] + row["mac-address"]),
        address = row["address"].orEmpty(),
        mac = row["mac-address"],
        interfaceName = row["interface"],
        complete = !truthy(row["incomplete"]),
    )

    fun routeFromApi(row: Map<String, String>): RouteEntry = RouteEntry(
        id = row[".id"] ?: row["dst-address"].orEmpty(),
        dstAddress = row["dst-address"].orEmpty(),
        gateway = row["gateway"],
        interfaceName = row["immediate-gw"] ?: row["gateway"],
        distance = row["distance"]?.toIntOrNull(),
        active = truthy(row["active"]),
        staticRoute = (row["static"]?.let { truthy(it) } ?: false) || row["connect"] == null,
    )

    fun logFromApi(row: Map<String, String>, index: Int): LogEntry = LogEntry(
        id = row[".id"] ?: index.toString(),
        time = row["time"],
        topics = row["topics"],
        message = row["message"].orEmpty(),
    )

    fun jsonToMap(obj: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k) ?: continue
            if (v == JSONObject.NULL) continue
            out[k] = v.toString()
        }
        return out
    }

    fun truthy(v: String?): Boolean {
        if (v == null) return false
        return when (v.trim().lowercase()) {
            "true", "yes", "1", "on" -> true
            else -> false
        }
    }
}

class MikroTikProvider(
    private val config: DeviceConnectionConfig,
    private val passwordProvider: () -> CharArray,
) : com.noc.monitor.protocol.DeviceProvider {
    override val capabilities = com.noc.monitor.protocol.DeviceCapabilities.mikroTik()

    private var api: RouterOsApiClient? = null
    private var rest: RouterOsRestClient? = null

    private val useRest get() = config.transport == Transport.REST
    private val useTls get() = config.transport == Transport.API_SSL

    override suspend fun testConnection(): NocResult<SystemInfo> = NocResult.catch { readSystemInternal() }

    override suspend fun readSystem(): NocResult<SystemInfo> = NocResult.catch { readSystemInternal() }

    override suspend fun readInterfaces(): NocResult<List<NetInterface>> = NocResult.catch {
        if (useRest) {
            restClient().getArray("/interface").map { RouterOsMapper.interfaceFromJson(it) }
        } else {
            apiClient().print("/interface/print").map { RouterOsMapper.interfaceFromApi(it) }
        }
    }

    override suspend fun readTraffic(): NocResult<List<TrafficCounters>> {
        val ifaces = readInterfaces()
        return ifaces.map { list ->
            list.map {
                TrafficCounters(
                    interfaceName = it.name,
                    rxBytes = it.rxBytes,
                    txBytes = it.txBytes,
                    rxPackets = it.rxPackets,
                    txPackets = it.txPackets,
                )
            }
        }
    }

    override suspend fun readPppoe(): NocResult<List<PppoeSession>> = NocResult.catch {
        try {
            if (useRest) {
                restClient().getArray("/ppp/active").map { RouterOsMapper.pppoeFromJson(it) }
            } else {
                apiClient().print("/ppp/active/print").map { RouterOsMapper.pppoeFromApi(it) }
            }
        } catch (e: NocException) {
            if (e.error is NocError.PermissionDenied || isMissingPackage(e)) {
                return@catch emptyList()
            }
            throw e
        }
    }

    override suspend fun disconnectPppoe(sessionId: String): NocResult<CommandOutcome> = NocResult.catch {
        if (useRest) {
            restClient().delete("/ppp/active/${enc(sessionId)}")
        } else {
            apiClient().command("/ppp/active/remove", listOf(RouterOsCodec.apiId(sessionId)))
        }
        CommandOutcome(true, "/ppp/active/remove", "PPPoE session $sessionId disconnected")
    }

    override suspend fun setInterfaceEnabled(interfaceId: String, enabled: Boolean): NocResult<CommandOutcome> =
        NocResult.catch {
            val flag = if (enabled) "no" else "yes"
            if (useRest) {
                restClient().patch("/interface/${enc(interfaceId)}", """{"disabled":"${if (enabled) "false" else "true"}"}""")
            } else {
                apiClient().command(
                    "/interface/set",
                    listOf(RouterOsCodec.apiId(interfaceId), RouterOsCodec.attr("disabled", flag)),
                )
            }
            val state = if (enabled) "enabled" else "disabled"
            CommandOutcome(true, "/interface/set", "Interface $interfaceId $state")
        }

    override suspend fun reboot(): NocResult<CommandOutcome> = NocResult.catch {
        try {
            if (useRest) {
                restClient().post("/system/reboot", "{}")
            } else {
                apiClient().command("/system/reboot")
            }
        } catch (t: Throwable) {
            // Router often drops the TCP session immediately after accepting reboot.
            val msg = t.message.orEmpty().lowercase()
            if (msg.contains("closed") || msg.contains("timeout") || t is java.io.EOFException) {
                return@catch CommandOutcome(true, "/system/reboot", "Reboot command accepted; connection closed by device")
            }
            throw t
        }
        CommandOutcome(true, "/system/reboot", "Reboot command sent")
    }

    override suspend fun readIpAddresses(): NocResult<List<IpAddressEntry>> = NocResult.catch {
        if (useRest) restClient().getArray("/ip/address").map { RouterOsMapper.ipFromApi(RouterOsMapper.jsonToMap(it)) }
        else apiClient().print("/ip/address/print").map { RouterOsMapper.ipFromApi(it) }
    }

    override suspend fun readDhcpLeases(): NocResult<List<DhcpLease>> = NocResult.catch {
        try {
            if (useRest) restClient().getArray("/ip/dhcp-server/lease").map { RouterOsMapper.dhcpFromApi(RouterOsMapper.jsonToMap(it)) }
            else apiClient().print("/ip/dhcp-server/lease/print").map { RouterOsMapper.dhcpFromApi(it) }
        } catch (e: NocException) {
            if (isMissingPackage(e)) emptyList() else throw e
        }
    }

    override suspend fun readArp(): NocResult<List<ArpEntry>> = NocResult.catch {
        if (useRest) restClient().getArray("/ip/arp").map { RouterOsMapper.arpFromApi(RouterOsMapper.jsonToMap(it)) }
        else apiClient().print("/ip/arp/print").map { RouterOsMapper.arpFromApi(it) }
    }

    override suspend fun readRoutes(): NocResult<List<RouteEntry>> = NocResult.catch {
        if (useRest) restClient().getArray("/ip/route").map { RouterOsMapper.routeFromApi(RouterOsMapper.jsonToMap(it)) }
        else apiClient().print("/ip/route/print").map { RouterOsMapper.routeFromApi(it) }
    }

    override suspend fun readLogs(limit: Int): NocResult<List<LogEntry>> = NocResult.catch {
        val rows = if (useRest) {
            restClient().getArray("/log").map { RouterOsMapper.jsonToMap(it) }
        } else {
            apiClient().print("/log/print")
        }
        rows.takeLast(limit).mapIndexed { i, row -> RouterOsMapper.logFromApi(row, i) }
    }

    override fun close() {
        try {
            api?.close()
        } catch (_: Throwable) {
        }
        try {
            rest?.close()
        } catch (_: Throwable) {
        }
        api = null
        rest = null
    }

    private fun readSystemInternal(): SystemInfo {
        return if (useRest) {
            val client = restClient()
            val identity = client.getObject("/system/identity")
            val resource = client.getObject("/system/resource")
            val health = try {
                client.getObject("/system/health")
            } catch (_: Throwable) {
                JSONObject()
            }
            RouterOsMapper.systemFromJson(identity, resource, health)
        } else {
            val client = apiClient()
            val identity = client.print("/system/identity/print").firstOrNull() ?: emptyMap()
            val resource = client.print("/system/resource/print").firstOrNull() ?: emptyMap()
            val health = try {
                client.print("/system/health/print").firstOrNull() ?: emptyMap()
            } catch (_: Throwable) {
                emptyMap()
            }
            RouterOsMapper.systemFromApi(identity, resource, health)
        }
    }

    @Synchronized
    private fun apiClient(): RouterOsApiClient {
        val existing = api
        if (existing != null && existing.isConnected) return existing
        existing?.close()
        val created = RouterOsApiClient(
            host = config.host,
            port = config.port,
            useTls = useTls,
            allowInsecureTls = config.allowInsecureTls,
            timeoutMs = config.timeoutMs,
        )
        created.connect()
        created.login(config.username, passwordProvider())
        api = created
        return created
    }

    @Synchronized
    private fun restClient(): RouterOsRestClient {
        rest?.let { return it }
        val created = RouterOsRestClient(
            host = config.host,
            port = config.port,
            username = config.username,
            password = passwordProvider(),
            useHttps = true,
            allowInsecureTls = config.allowInsecureTls,
            timeoutMs = config.timeoutMs,
        )
        rest = created
        return created
    }

    private fun enc(id: String): String = java.net.URLEncoder.encode(id, Charsets.UTF_8.name()).replace("+", "%20")

    private fun isMissingPackage(e: NocException): Boolean {
        val m = (e.error as? NocError.OperationFailed)?.detail?.lowercase()
            ?: (e.error as? NocError.PermissionDenied)?.detail?.lowercase()
            ?: e.message?.lowercase().orEmpty()
        return m.contains("no such command") || m.contains("not found") || m.contains("no such item") ||
            m.contains("unknown command")
    }
}
