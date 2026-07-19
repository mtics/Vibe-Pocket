package au.edu.uts.vibepocket.bridge

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClientLimitTest {
    @Test
    fun declaredOversizeResponseIsRejectedBeforeReading() {
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(): Int = error("The oversized body must not be read.")
        }

        assertThrows(Failure::class.java) {
            readResponse(input, contentLength = 9, limit = 8)
        }
    }

    @Test
    fun streamingOversizeResponseIsRejectedWithoutContentLength() {
        assertThrows(Failure::class.java) {
            readResponse(ByteArrayInputStream(ByteArray(9)), contentLength = -1, limit = 8)
        }
    }

    @Test
    fun boundedEventParserDeliversSnapshotAndId() {
        val ids = mutableListOf<String>()
        var changes = 0

        consumeEvents(
            input = ByteArrayInputStream("id: 42\nevent: snapshot_changed\n\n".toByteArray()),
            onEventId = ids::add,
            onSnapshotChanged = { changes += 1 },
        )

        assertEquals(listOf("42"), ids)
        assertEquals(1, changes)
    }

    @Test
    fun endlessEventLineIsRejectedAtTheByteLimit() {
        val input = ByteArrayInputStream(ByteArray(MaxEventLineBytes + 1) { 'a'.code.toByte() })

        assertThrows(Failure::class.java) {
            consumeEvents(input, onEventId = {}, onSnapshotChanged = {})
        }
    }
}
