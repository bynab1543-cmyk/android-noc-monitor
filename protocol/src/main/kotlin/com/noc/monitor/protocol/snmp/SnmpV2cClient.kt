package com.noc.monitor.protocol.snmp

import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import com.noc.monitor.protocol.PortPolicy
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal SNMPv2c GET client. Speaks real UDP/161 (never port 9).
 */
class SnmpV2cClient(
    private val host: String,
    private val port: Int = PortPolicy.DEFAULT_SNMP,
    private val community: String,
    private val timeoutMs: Int = 4_000,
) {
    init {
        PortPolicy.requireAllowed(port)
    }

    fun get(oids: List<String>): Map<String, String> {
        if (oids.isEmpty()) return emptyMap()
        PortPolicy.requireAllowed(port)
        val requestId = REQUEST_ID.incrementAndGet() and 0x7FFFFFFF
        val payload = Ber.encodeMessage(community, requestId, oids)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = try {
                InetAddress.getByName(host)
            } catch (e: java.net.UnknownHostException) {
                throw NocException(NocError.UnknownHost(host), e)
            }
            try {
                socket.send(DatagramPacket(payload, payload.size, address, port))
            } catch (e: java.net.ConnectException) {
                throw NocException(NocError.ConnectionRefused(host, port), e)
            }
            val buf = ByteArray(8 * 1024)
            val incoming = DatagramPacket(buf, buf.size)
            try {
                socket.receive(incoming)
            } catch (e: java.net.SocketTimeoutException) {
                throw NocException(NocError.Timeout(host, port), e)
            }
            return Ber.decodeGetResponse(incoming.data.copyOf(incoming.length))
        }
    }

    companion object {
        private val REQUEST_ID = AtomicInteger(1)
    }
}

internal object Ber {
    fun encodeMessage(community: String, requestId: Int, oids: List<String>): ByteArray {
        val pdu = ByteArrayOutputStream()
        pdu.write(encodeInteger(requestId))
        pdu.write(encodeInteger(0))
        pdu.write(encodeInteger(0))
        val binds = ByteArrayOutputStream()
        for (oid in oids) {
            val bind = encodeSequence(encodeOid(oid) + encodeNull())
            binds.write(bind)
        }
        pdu.write(encodeSequence(binds.toByteArray()))
        val pduBytes = encodeTlv(0xA0, pdu.toByteArray())
        val body = encodeInteger(1) + encodeOctetString(community.toByteArray(Charsets.US_ASCII)) + pduBytes
        return encodeSequence(body)
    }

    fun decodeGetResponse(data: ByteArray): Map<String, String> {
        val msg = decodeTlv(data, 0)
        if (msg.tag != 0x30) throw NocException(NocError.Protocol("SNMP: expected SEQUENCE"))
        var p = 0
        val inner = msg.value
        val version = decodeTlv(inner, p); p += version.consumed
        val comm = decodeTlv(inner, p); p += comm.consumed
        val pdu = decodeTlv(inner, p)
        if (pdu.tag != 0xA2 && pdu.tag != 0xA0) {
            throw NocException(NocError.Protocol("SNMP: unexpected PDU 0x${pdu.tag.toString(16)}"))
        }
        var q = 0
        val req = decodeTlv(pdu.value, q); q += req.consumed
        val err = decodeTlv(pdu.value, q); q += err.consumed
        val errIdx = decodeTlv(pdu.value, q); q += errIdx.consumed
        val errorStatus = decodeInteger(err)
        if (errorStatus != 0) {
            throw NocException(NocError.PermissionDenied("SNMP error-status $errorStatus"))
        }
        val bindings = decodeTlv(pdu.value, q)
        val out = LinkedHashMap<String, String>()
        var r = 0
        while (r < bindings.value.size) {
            val bind = decodeTlv(bindings.value, r)
            r += bind.consumed
            var s = 0
            val name = decodeTlv(bind.value, s); s += name.consumed
            val value = decodeTlv(bind.value, s)
            val oid = decodeOid(name)
            val decoded = decodeValue(value)
            if (decoded != null) out[oid] = decoded
        }
        return out
    }

    private data class Tlv(val tag: Int, val value: ByteArray, val consumed: Int)

    private fun encodeTlv(tag: Int, value: ByteArray): ByteArray {
        return byteArrayOf(tag.toByte()) + encodeLength(value.size) + value
    }

    private fun encodeSequence(value: ByteArray) = encodeTlv(0x30, value)
    private fun encodeNull() = byteArrayOf(0x05, 0x00)
    private fun encodeOctetString(value: ByteArray) = encodeTlv(0x04, value)

    private fun encodeInteger(value: Int): ByteArray {
        var v = value
        val bytes = ArrayList<Byte>()
        do {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        } while (v != 0 && v != -1)
        if (bytes.isEmpty()) bytes.add(0)
        if (value >= 0 && (bytes.first().toInt() and 0x80) != 0) bytes.add(0, 0)
        if (value < 0 && (bytes.first().toInt() and 0x80) == 0) bytes.add(0, 0xFF.toByte())
        return encodeTlv(0x02, bytes.toByteArray())
    }

    private fun encodeOid(oid: String): ByteArray {
        val parts = oid.trim('.').split('.').map { it.toInt() }
        require(parts.size >= 2)
        val out = ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var n = parts[i]
            if (n < 0) n = 0
            val stack = ArrayList<Int>()
            if (n == 0) stack.add(0)
            while (n > 0) {
                stack.add(n and 0x7F)
                n = n shr 7
            }
            for (j in stack.size - 1 downTo 0) {
                val b = if (j == 0) stack[j] else stack[j] or 0x80
                out.write(b)
            }
        }
        return encodeTlv(0x06, out.toByteArray())
    }

    private fun encodeLength(len: Int): ByteArray {
        return if (len < 0x80) byteArrayOf(len.toByte())
        else if (len < 0x100) byteArrayOf(0x81.toByte(), len.toByte())
        else byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
    }

    private fun decodeTlv(data: ByteArray, offset: Int): Tlv {
        if (offset >= data.size) throw NocException(NocError.Protocol("SNMP truncated"))
        val tag = data[offset].toInt() and 0xFF
        var i = offset + 1
        var len = data[i].toInt() and 0xFF
        i++
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            len = 0
            repeat(n) {
                len = (len shl 8) + (data[i].toInt() and 0xFF)
                i++
            }
        }
        val value = data.copyOfRange(i, i + len)
        return Tlv(tag, value, i + len - offset)
    }

    private fun decodeInteger(tlv: Tlv): Int = decodeUnsigned(tlv).toInt()

    private fun decodeUnsigned(tlv: Tlv): Long {
        var v = 0L
        for (b in tlv.value) v = (v shl 8) or (b.toInt() and 0xFF).toLong()
        return v
    }

    private fun decodeOid(tlv: Tlv): String {
        if (tlv.value.isEmpty()) return ""
        val first = tlv.value[0].toInt() and 0xFF
        val parts = ArrayList<Int>()
        parts += first / 40
        parts += first % 40
        var i = 1
        var acc = 0
        while (i < tlv.value.size) {
            val b = tlv.value[i].toInt() and 0xFF
            acc = (acc shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) {
                parts += acc
                acc = 0
            }
            i++
        }
        return parts.joinToString(".")
    }

    private fun decodeValue(tlv: Tlv): String? {
        return when (tlv.tag) {
            0x02, 0x41, 0x42, 0x43, 0x46 -> decodeUnsigned(tlv).toString()
            0x04 -> if (tlv.value.size == 6) {
                tlv.value.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            } else tlv.value.toString(Charsets.ISO_8859_1)
            0x06 -> decodeOid(tlv)
            0x05 -> null
            0x40 -> tlv.value.joinToString(".") { (it.toInt() and 0xFF).toString() }
            else -> tlv.value.toString(Charsets.ISO_8859_1).ifBlank { null }
        }
    }
}
