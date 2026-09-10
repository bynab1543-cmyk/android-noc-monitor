package com.noc.monitor.lab

import com.noc.monitor.protocol.PortPolicy
import com.noc.monitor.protocol.routeros.RouterOsCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks the real RouterOS API binary protocol so the production client can be
 * exercised without a physical router. This is a lab endpoint, not DEMO mode.
 */
class RouterOsLabSimulator(
    val username: String = "admin",
    val password: String = "labpass",
    val bindHost: String = "127.0.0.1",
    private val requestedPort: Int = 0,
    val identity: String = "noc-lab-gw",
) : AutoCloseable {
    init {
        if (requestedPort != 0) PortPolicy.requireAllowed(requestedPort)
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    val interfaces = ConcurrentHashMap<String, MutableMap<String, String>>()
    val pppoe = ConcurrentHashMap<String, MutableMap<String, String>>()
    val addresses = ConcurrentHashMap<String, MutableMap<String, String>>()
    val leases = ConcurrentHashMap<String, MutableMap<String, String>>()
    val arp = ConcurrentHashMap<String, MutableMap<String, String>>()
    val routes = ConcurrentHashMap<String, MutableMap<String, String>>()
    val logs = mutableListOf<MutableMap<String, String>>()
    @Volatile var cpuLoad = 7
    @Volatile var version = "7.15.3 (stable)"
    @Volatile var boardName = "RB5009UG+S+"
    @Volatile var uptime = "1d2h15m"
    @Volatile var temperature = "41"
    @Volatile var acceptLogins = true
    @Volatile var delayMs: Long = 0
    @Volatile var rebootReceived = false
    private val rxBase = AtomicLong(1_000_000)
    private val txBase = AtomicLong(500_000)

    val port: Int
        get() = server?.localPort ?: 0

    fun start(): Int {
        PortPolicy.requireAllowed(if (requestedPort == 0) PortPolicy.DEFAULT_API else requestedPort)
        seed()
        val ss = if (requestedPort == 0) {
            ServerSocket(0)
        } else {
            ServerSocket(requestedPort)
        }
        server = ss
        running.set(true)
        pool.execute {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    pool.execute { handle(client) }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
        return ss.localPort
    }

    fun seed() {
        interfaces.clear()
        putIface("*1", "ether1", "ether", running = true, disabled = false)
        putIface("*2", "ether2", "ether", running = true, disabled = false)
        putIface("*3", "pppoe-out1", "pppoe-out", running = true, disabled = false)
        putIface("*4", "bridge", "bridge", running = true, disabled = false)
        putIface("*5", "sfp-sfpplus1", "ether", running = false, disabled = true)
        pppoe["*A"] = mutableMapOf(
            ".id" to "*A",
            "name" to "user-ahmed",
            "service" to "pppoe-in",
            "address" to "10.10.0.25",
            "caller-id" to "aa:bb:cc:dd:ee:01",
            "uptime" to "3h12m",
            "encoding" to "MPPE128",
        )
        pppoe["*B"] = mutableMapOf(
            ".id" to "*B",
            "name" to "user-sara",
            "service" to "pppoe-in",
            "address" to "10.10.0.26",
            "caller-id" to "aa:bb:cc:dd:ee:02",
            "uptime" to "18m",
            "encoding" to "MPPE128",
        )
        addresses["*I1"] = mutableMapOf(".id" to "*I1", "address" to "192.168.88.1/24", "interface" to "bridge", "network" to "192.168.88.0")
        leases["*D1"] = mutableMapOf(".id" to "*D1", "address" to "192.168.88.20", "mac-address" to "11:22:33:44:55:66", "host-name" to "ap-office", "status" to "bound")
        arp["*R1"] = mutableMapOf(".id" to "*R1", "address" to "192.168.88.20", "mac-address" to "11:22:33:44:55:66", "interface" to "bridge")
        routes["*G1"] = mutableMapOf(".id" to "*G1", "dst-address" to "0.0.0.0/0", "gateway" to "192.168.88.254", "distance" to "1", "active" to "true")
        synchronized(logs) {
            logs.clear()
            logs += mutableMapOf("time" to "21:01:02", "topics" to "pppoe,info", "message" to "pppoe login: user-ahmed")
            logs += mutableMapOf("time" to "21:04:11", "topics" to "system,info", "message" to "interface ether2 link up")
        }
    }

    private fun putIface(id: String, name: String, type: String, running: Boolean, disabled: Boolean) {
        interfaces[id] = mutableMapOf(
            ".id" to id,
            "name" to name,
            "type" to type,
            "running" to if (running) "true" else "false",
            "disabled" to if (disabled) "true" else "false",
            "mac-address" to "48:8F:5A:00:00:0${id.last()}",
            "rx-byte" to rxBase.get().toString(),
            "tx-byte" to txBase.get().toString(),
            "rx-packet" to "1200",
            "tx-packet" to "900",
            "mtu" to "1500",
        )
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var authed = false
        try {
            while (running.get() && !socket.isClosed) {
                if (delayMs > 0) Thread.sleep(delayMs)
                val words = RouterOsCodec.readSentence(input)
                if (words.isEmpty()) continue
                val cmd = words.first()
                val attrs = parse(words)
                when {
                    cmd == "/login" -> {
                        val name = attrs["name"]
                        val pwd = attrs["password"]
                        if (!acceptLogins) {
                            writeTrap(output, "login not allowed")
                            continue
                        }
                        if (name == null) {
                            writeDone(output, mapOf("ret" to "00112233445566778899aabbccddeeff"))
                            continue
                        }
                        if (name == username && pwd == password) {
                            authed = true
                            writeDone(output)
                        } else {
                            writeTrap(output, "invalid user name or password")
                        }
                    }
                    !authed -> writeTrap(output, "not logged in")
                    cmd == "/system/identity/print" -> writeRecords(output, listOf(mapOf("name" to identity)))
                    cmd == "/system/resource/print" -> writeRecords(
                        output,
                        listOf(
                            mapOf(
                                "uptime" to uptime,
                                "version" to version,
                                "cpu-load" to cpuLoad.toString(),
                                "cpu-count" to "4",
                                "architecture-name" to "arm64",
                                "board-name" to boardName,
                                "platform" to "MikroTik",
                                "free-memory" to "180000000",
                                "total-memory" to "268435456",
                                "free-hdd-space" to "800000000",
                                "total-hdd-space" to "1073741824",
                            ),
                        ),
                    )
                    cmd == "/system/health/print" -> writeRecords(output, listOf(mapOf("temperature" to temperature)))
                    cmd == "/interface/print" -> {
                        bumpTraffic()
                        writeRecords(output, interfaces.values.map { it.toMap() })
                    }
                    cmd == "/interface/set" -> {
                        val id = attrs[".id"] ?: attrs["id"]
                        val target = interfaces[id] ?: interfaces.values.firstOrNull { it["name"] == attrs["name"] }
                        if (target == null) {
                            writeTrap(output, "no such item")
                        } else {
                            attrs["disabled"]?.let { target["disabled"] = it }
                            if (attrs["disabled"] == "yes") target["running"] = "false"
                            if (attrs["disabled"] == "no") target["running"] = "true"
                            writeDone(output)
                        }
                    }
                    cmd == "/ppp/active/print" -> writeRecords(output, pppoe.values.map { it.toMap() })
                    cmd == "/ppp/active/remove" -> {
                        val id = attrs[".id"]
                        if (id != null && pppoe.remove(id) != null) writeDone(output) else writeTrap(output, "no such item")
                    }
                    cmd == "/system/reboot" -> {
                        rebootReceived = true
                        writeDone(output)
                        socket.close()
                        return
                    }
                    cmd == "/ip/address/print" -> writeRecords(output, addresses.values.map { it.toMap() })
                    cmd == "/ip/dhcp-server/lease/print" -> writeRecords(output, leases.values.map { it.toMap() })
                    cmd == "/ip/arp/print" -> writeRecords(output, arp.values.map { it.toMap() })
                    cmd == "/ip/route/print" -> writeRecords(output, routes.values.map { it.toMap() })
                    cmd == "/log/print" -> {
                        val snapshot = synchronized(logs) { logs.map { it.toMap() } }
                        writeRecords(output, snapshot)
                    }
                    else -> writeTrap(output, "unknown command '$cmd'")
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun bumpTraffic() {
        val rx = rxBase.addAndGet(125_000)
        val tx = txBase.addAndGet(62_000)
        for (iface in interfaces.values) {
            if (iface["disabled"] == "true" || iface["disabled"] == "yes") continue
            val curRx = iface["rx-byte"]?.toLongOrNull() ?: rx
            val curTx = iface["tx-byte"]?.toLongOrNull() ?: tx
            iface["rx-byte"] = (curRx + 125_000).toString()
            iface["tx-byte"] = (curTx + 62_000).toString()
            iface["rx-packet"] = ((iface["rx-packet"]?.toLongOrNull() ?: 0) + 80).toString()
            iface["tx-packet"] = ((iface["tx-packet"]?.toLongOrNull() ?: 0) + 40).toString()
        }
    }

    private fun parse(words: List<String>): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        for (i in 1 until words.size) {
            val w = words[i]
            if (w.startsWith("=")) {
                val eq = w.indexOf('=', 1)
                if (eq > 0) attrs[w.substring(1, eq)] = w.substring(eq + 1)
            } else if (w.startsWith(".")) {
                val eq = w.indexOf('=')
                if (eq > 0) attrs[w.substring(0, eq)] = w.substring(eq + 1)
            }
        }
        return attrs
    }

    private fun writeRecords(output: java.io.OutputStream, rows: List<Map<String, String>>) {
        for (row in rows) {
            val words = ArrayList<String>(row.size + 1)
            words += "!re"
            for ((k, v) in row) {
                words += if (k.startsWith(".")) "$k=$v" else "=$k=$v"
            }
            RouterOsCodec.writeSentence(output, words)
        }
        writeDone(output)
    }

    private fun writeDone(output: java.io.OutputStream, extra: Map<String, String> = emptyMap()) {
        val words = ArrayList<String>(extra.size + 1)
        words += "!done"
        for ((k, v) in extra) words += "=$k=$v"
        RouterOsCodec.writeSentence(output, words)
    }

    private fun writeTrap(output: java.io.OutputStream, message: String) {
        RouterOsCodec.writeSentence(output, listOf("!trap", "=message=$message"))
        RouterOsCodec.writeSentence(output, listOf("!done"))
    }

    override fun close() {
        running.set(false)
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        pool.shutdownNow()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val bindPort = args.firstOrNull()?.toIntOrNull() ?: PortPolicy.DEFAULT_API
            PortPolicy.requireAllowed(bindPort)
            val sim = RouterOsLabSimulator(requestedPort = bindPort)
            val actual = sim.start()
            println("RouterOS lab simulator listening on ${sim.bindHost}:$actual (API). Label this endpoint LAB, not DEMO.")
            println("Username=${sim.username}  (password not printed)")
            Thread.currentThread().join()
        }
    }
}
