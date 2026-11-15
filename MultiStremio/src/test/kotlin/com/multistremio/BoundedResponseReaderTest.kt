package com.multistremio

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedResponseReaderTest {
    @Test
    fun acceptsBodyExactlyAtByteLimit() {
        val bytes = """{"streams":[]}""".toByteArray(Charsets.UTF_8)

        assertEquals(
            String(bytes, Charsets.UTF_8),
            readBoundedUtf8(ByteArrayInputStream(bytes), bytes.size.toLong()),
        )
    }

    @Test
    fun rejectsBodyOneByteOverLimit() {
        val bytes = """{"streams":[]}""".toByteArray(Charsets.UTF_8)

        assertNull(readBoundedUtf8(ByteArrayInputStream(bytes), bytes.size.toLong() - 1L))
    }

    @Test
    fun enforcesByteLimitForMultibyteUtf8TextAndStripsBom() {
        val payload = "\uFEFFবাংলা"
        val bytes = payload.toByteArray(Charsets.UTF_8)

        assertNull(readBoundedUtf8(ByteArrayInputStream(bytes), bytes.size.toLong() - 1L))
        assertEquals("বাংলা", readBoundedUtf8(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    @Test
    fun permitsAnEmptyBodyWithAZeroByteLimit() {
        assertEquals("", readBoundedUtf8(ByteArrayInputStream(ByteArray(0)), 0L))
    }
}
