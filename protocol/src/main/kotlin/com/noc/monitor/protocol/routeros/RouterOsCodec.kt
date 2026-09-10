package com.noc.monitor.protocol.routeros

import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * MikroTik RouterOS API binary sentence codec.
 *
 * Wire format: each word is a length prefix (1–5 bytes) followed by UTF-8 bytes.
 * A sentence is a sequence of words terminated by a zero-length word.
 *
 * Compatible with RouterOS API (TCP 8728) and API-SSL (TCP 8729).
 */
object RouterOsCodec {
    private val utf8 = StandardCharsets.UTF_8

    fun encodeLength(length: Int): ByteArray {
        require(length >= 0) { "length must be >= 0" }
        return when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length < 0x4000 -> {
                val v = length or 0x8000
                byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            }
            length < 0x200000 -> {
                val v = length or 0xC00000
                byteArrayOf(
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    (v and 0xFF).toByte(),
                )
            }
            length < 0x10000000 -> {
                val v = length or 0xE0000000.toInt()
                byteArrayOf(
                    ((v shr 24) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    (v and 0xFF).toByte(),
                )
            }
            else -> {
                byteArrayOf(
                    0xF0.toByte(),
                    ((length shr 24) and 0xFF).toByte(),
                    ((length shr 16) and 0xFF).toByte(),
                    ((length shr 8) and 0xFF).toByte(),
                    (length and 0xFF).toByte(),
                )
            }
        }
    }

    fun decodeLength(input: InputStream): Int {
        val first = input.read()
        if (first < 0) throw EOFException("Connection closed while reading length")
        return when {
            first and 0x80 == 0x00 -> first
            first and 0xC0 == 0x80 -> {
                val second = readByte(input)
                ((first and 0x3F) shl 8) + second
            }
            first and 0xE0 == 0xC0 -> {
                val b1 = readByte(input)
                val b2 = readByte(input)
                ((first and 0x1F) shl 16) + (b1 shl 8) + b2
            }
            first and 0xF0 == 0xE0 -> {
                val b1 = readByte(input)
                val b2 = readByte(input)
                val b3 = readByte(input)
                ((first and 0x0F) shl 24) + (b1 shl 16) + (b2 shl 8) + b3
            }
            first and 0xF8 == 0xF0 -> {
                val b1 = readByte(input)
                val b2 = readByte(input)
                val b3 = readByte(input)
                val b4 = readByte(input)
                (b1 shl 24) + (b2 shl 16) + (b3 shl 8) + b4
            }
            else -> throw NocException(NocError.Protocol("Unknown length control byte: 0x${first.toString(16)}"))
        }
    }

    fun encodeWord(word: String): ByteArray {
        val bytes = word.toByteArray(utf8)
        val out = ByteArrayOutputStream(bytes.size + 5)
        out.write(encodeLength(bytes.size))
        out.write(bytes)
        return out.toByteArray()
    }

    fun encodeSentence(words: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        for (word in words) {
            out.write(encodeWord(word))
        }
        out.write(encodeLength(0))
        return out.toByteArray()
    }

    fun writeSentence(output: OutputStream, words: List<String>) {
        output.write(encodeSentence(words))
        output.flush()
    }

    fun readSentence(input: InputStream): List<String> {
        val words = ArrayList<String>(8)
        while (true) {
            val len = decodeLength(input)
            if (len == 0) break
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) throw EOFException("Connection closed while reading word")
                off += n
            }
            words.add(String(buf, utf8))
        }
        return words
    }

    fun parseAttributes(words: List<String>): Pair<String, Map<String, String>> {
        if (words.isEmpty()) return "" to emptyMap()
        val tag = words.first()
        val attrs = LinkedHashMap<String, String>()
        for (i in 1 until words.size) {
            val w = words[i]
            when {
                w.startsWith("=") -> {
                    val eq = w.indexOf('=', 1)
                    if (eq > 0) {
                        val key = w.substring(1, eq)
                        val value = w.substring(eq + 1)
                        attrs[key] = value
                    } else if (w.length > 1) {
                        attrs[w.substring(1)] = ""
                    }
                }
                w.startsWith(".") -> {
                    val eq = w.indexOf('=')
                    if (eq > 0) {
                        attrs[w.substring(0, eq)] = w.substring(eq + 1)
                    }
                }
            }
        }
        return tag to attrs
    }

    fun attr(key: String, value: String): String = "=$key=$value"
    fun apiId(id: String): String = "=.id=$id"
    fun query(key: String, value: String): String = "?$key=$value"

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Connection closed while reading length")
        return b
    }
}
