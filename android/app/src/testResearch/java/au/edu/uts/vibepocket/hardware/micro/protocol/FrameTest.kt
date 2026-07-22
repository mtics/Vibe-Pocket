package au.edu.uts.vibepocket.hardware.micro.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class FrameTest {
    @Test
    fun deviceFramesAppendNewlineAndStayFixedWidth() {
        val source = JSONObject().put("method", "v.oai.hid").put("text", "x".repeat(80)).toString()

        val reports = DeviceEncoder.encode(source)

        assertTrue(reports.size > 1)
        assertTrue(reports.all { it.size == Frame.bodySize && it[0].toInt() == Frame.channel })
        assertEquals("$source\n", reports.payload())
    }

    @Test
    fun hostFramesCompleteWithoutNewline() {
        val source = JSONObject().put("method", "device.status").put("id", 17).toString()
        val decoder = HostDecoder()
        val fragments = hostFrames(source, 9)

        fragments.dropLast(1).forEach { assertEquals(Decode.Pending, decoder.acceptBody(it)) }
        val result = decoder.acceptBody(fragments.last()) as Decode.Complete

        assertEquals("device.status", JSONObject(result.json).getString("method"))
        assertEquals(17, JSONObject(result.json).getInt("id"))
    }

    @Test
    fun hostAcceptsExplicitReportIdOnlyWhenItIsSix() {
        val source = "{\"method\":\"sys.version\",\"id\":1}"
        val body = hostFrames(source).single()

        assertTrue(HostDecoder().acceptReport(byteArrayOf(6) + body) is Decode.Complete)
        assertTrue(HostDecoder().acceptReport(byteArrayOf(5) + body) is Decode.Invalid)
        assertTrue(HostDecoder().acceptBody(byteArrayOf(6) + body) is Decode.Invalid)
    }

    @Test
    fun normalizationAcceptsOnlyBleBodiesOrUsbReportsWithIdSix() {
        val body = hostFrames("{\"method\":\"sys.version\",\"id\":1}").single()

        assertEquals(body.asList(), Frame.normalize(body)?.asList())
        assertEquals(body.asList(), Frame.normalize(byteArrayOf(6) + body)?.asList())
        assertEquals(null, Frame.normalize(byteArrayOf(5) + body))
        assertEquals(null, Frame.normalize(ByteArray(62)))
    }

    @Test
    fun malformedLengthAndPaddingResetTheDecoder() {
        val oversized = ByteArray(Frame.bodySize).also {
            it[0] = Frame.channel.toByte()
            it[1] = 62
        }
        val padded = hostFrames("{\"method\":\"sys.version\",\"id\":1}").single().also {
            it[it.lastIndex] = 1
        }

        assertTrue(HostDecoder().acceptBody(oversized) is Decode.Invalid)
        assertTrue(HostDecoder().acceptBody(padded) is Decode.Invalid)
    }

    @Test
    fun fragmentedUtf8IsDecodedAfterAssembly() {
        val source = JSONObject().put("method", "v.oai.rgbcfg").put("label", "\u6d4b\u8bd5").toString()
        val decoder = HostDecoder()
        val results = hostFrames(source, 1).map(decoder::acceptBody)

        val result = results.last() as Decode.Complete
        assertEquals("\u6d4b\u8bd5", JSONObject(result.json).getString("label"))
    }

    @Test
    fun nestedObjectAtAFragmentBoundaryDoesNotRestartTheRequest() {
        val decoder = HostDecoder()
        val first = hostFrames("{\"method\":\"device.status\",\"params\":[").single()
        val second = hostFrames("{\"method\":\"nested\"}],\"id\":1}").single()

        assertEquals(Decode.Pending, decoder.acceptBody(first))
        val result = decoder.acceptBody(second) as Decode.Complete

        val json = JSONObject(result.json)
        assertEquals("device.status", json.getString("method"))
        assertEquals("nested", json.getJSONArray("params").getJSONObject(0).getString("method"))
    }

    @Test
    fun explicitResetStartsANewConnectionBoundary() {
        val decoder = HostDecoder()
        assertEquals(
            Decode.Pending,
            decoder.acceptBody(hostFrames("{\"method\":\"device.status\"").single()),
        )

        decoder.reset()
        val result = decoder.acceptBody(hostFrames("{\"method\":\"sys.version\",\"id\":2}").single())

        assertTrue(result is Decode.Complete)
        assertEquals("sys.version", JSONObject((result as Decode.Complete).json).getString("method"))
    }

    @Test
    fun bufferIsBoundedAndCanRecoverOnANewTopLevelRequest() {
        val decoder = HostDecoder()
        val oversized = "{\"method\":\"v.oai.rgbcfg\",\"value\":\"${"x".repeat(Frame.bufferLimit)}\"}"
        val result = hostFrames(oversized).map(decoder::acceptBody).last()
        assertTrue(result is Decode.Invalid)

        val recovered = decoder.acceptBody(hostFrames("{\"method\":\"sys.version\",\"id\":2}").single())
        assertTrue(recovered is Decode.Complete)
    }

    @Test
    fun hostRejectsWhitespaceOutsideTheJsonObject() {
        val leading = hostFrames(" {\"method\":\"sys.version\"}").single()
        val trailing = hostFrames("{\"method\":\"sys.version\"}\n").single()

        assertTrue(HostDecoder().acceptBody(leading) is Decode.Invalid)
        assertTrue(HostDecoder().acceptBody(trailing) is Decode.Invalid)
    }

    private fun hostFrames(source: String, chunk: Int = Frame.payloadSize): List<ByteArray> =
        source.toByteArray(StandardCharsets.UTF_8).asList().chunked(chunk).map { bytes ->
            ByteArray(Frame.bodySize).also { report ->
                report[0] = Frame.channel.toByte()
                report[1] = bytes.size.toByte()
                bytes.forEachIndexed { index, value -> report[index + 2] = value }
            }
        }

    private fun List<ByteArray>.payload(): String = flatMap { report ->
        report.copyOfRange(2, 2 + (report[1].toInt() and 0xff)).asList()
    }.toByteArray().toString(StandardCharsets.UTF_8)
}
