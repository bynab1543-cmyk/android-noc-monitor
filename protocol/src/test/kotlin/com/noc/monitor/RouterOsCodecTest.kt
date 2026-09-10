package com.noc.monitor

import com.noc.monitor.protocol.PortPolicy
import com.noc.monitor.protocol.routeros.RouterOsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertFailsWith

class RouterOsCodecTest {
    @Test
    fun lengthSingleByte() {
        assertArrayEquals(byteArrayOf(5), RouterOsCodec.encodeLength(5))
        assertEquals(5, RouterOsCodec.decodeLength(ByteArrayInputStream(byteArrayOf(5))))
    }

    @Test
    fun sentenceRoundTrip() {
        val words = listOf("/login", "=name=admin", "=password=secret")
        val encoded = RouterOsCodec.encodeSentence(words)
        val decoded = RouterOsCodec.readSentence(ByteArrayInputStream(encoded))
        assertEquals(words, decoded)
    }

    @Test
    fun emptyTerminator() {
        val out = ByteArrayOutputStream()
        RouterOsCodec.writeSentence(out, listOf("!done"))
        val decoded = RouterOsCodec.readSentence(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("!done"), decoded)
    }

    @Test
    fun parseAttributes() {
        val (tag, attrs) = RouterOsCodec.parseAttributes(
            listOf("!re", "=name=ether1", "=running=true", "=.id=*1"),
        )
        assertEquals("!re", tag)
        assertEquals("ether1", attrs["name"])
        assertEquals("true", attrs["running"])
        assertEquals("*1", attrs[".id"])
    }

    @Test
    fun twoByteLengthRoundTrip() {
        val word = "x".repeat(200)
        val encoded = RouterOsCodec.encodeSentence(listOf(word))
        val decoded = RouterOsCodec.readSentence(ByteArrayInputStream(encoded))
        assertEquals(listOf(word), decoded)
        assertTrue(encoded[0].toInt() and 0x80 != 0)
    }
}

class PortPolicyTest {
    @Test
    fun rejectsPort9() {
        assertTrue(PortPolicy.isForbidden(9))
        assertFailsWith<com.noc.monitor.protocol.NocException> {
            PortPolicy.requireAllowed(9)
        }
    }

    @Test
    fun allowsApiPorts() {
        PortPolicy.requireAllowed(8728)
        PortPolicy.requireAllowed(8729)
        PortPolicy.requireAllowed(443)
    }
}
