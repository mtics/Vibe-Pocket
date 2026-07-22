package au.edu.uts.vibepocket.hardware.micro.protocol

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object Frame {
    const val reportId = 6
    const val bodySize = 63
    const val payloadSize = 61
    const val channel = 2
    const val bufferLimit = 4096

    fun normalize(report: ByteArray): ByteArray? = when {
        report.size == bodySize -> report.copyOf()
        report.size == bodySize + 1 && report[0].toInt() and 0xff == reportId ->
            report.copyOfRange(1, report.size)
        else -> null
    }
}

sealed interface Decode {
    data object Pending : Decode
    data class Complete(val json: String) : Decode
    data class Invalid(val reason: String) : Decode
}

class HostDecoder {
    private val buffer = ByteArrayOutputStream()

    fun acceptBody(body: ByteArray): Decode {
        if (body.size != Frame.bodySize) return invalid("Expected a 63-byte BLE report body.")
        return accept(body)
    }

    fun acceptReport(report: ByteArray): Decode {
        val body = Frame.normalize(report)
            ?: return invalid("Expected a 64-byte USB report beginning with ID 6.")
        if (report.size != Frame.bodySize + 1) return invalid("Expected an explicit report ID.")
        return accept(body)
    }

    private fun accept(body: ByteArray): Decode {
        if (body[0].toInt() and 0xff != Frame.channel) return invalid("Unsupported report channel.")

        val length = body[1].toInt() and 0xff
        if (length > Frame.payloadSize) return invalid("Payload length exceeds 61 bytes.")
        if (body.copyOfRange(2 + length, body.size).any { it != 0.toByte() }) {
            return invalid("Report padding must be zeroed.")
        }

        val fragment = body.copyOfRange(2, 2 + length)
        if (buffer.size() + fragment.size > Frame.bufferLimit) return invalid("RPC buffer exceeds 4096 bytes.")
        buffer.write(fragment)

        return when (val boundary = boundary(buffer.toByteArray())) {
            Boundary.Incomplete -> Decode.Pending
            is Boundary.Invalid -> invalid(boundary.reason)
            is Boundary.Complete -> complete(boundary.end)
        }
    }

    fun reset() = buffer.reset()

    private fun complete(end: Int): Decode {
        val bytes = buffer.toByteArray()
        if (end != bytes.size) {
            return invalid("Trailing data follows the JSON object.")
        }
        val json = runCatching { bytes.copyOfRange(0, end).decodeUtf8() }
            .getOrElse { return invalid("RPC payload is not valid UTF-8.") }
        val parsed = runCatching { JSONObject(json) }
            .getOrElse { return invalid("RPC payload is not a JSON object.") }
        buffer.reset()
        return Decode.Complete(parsed.toString())
    }

    private fun invalid(reason: String): Decode.Invalid {
        buffer.reset()
        return Decode.Invalid(reason)
    }
}

object DeviceEncoder {
    fun encode(json: String): List<ByteArray> {
        require(runCatching { JSONObject(json) }.isSuccess) { "Device payload must be a JSON object." }
        val bytes = "$json\n".toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= Frame.bufferLimit) { "Device payload exceeds 4096 bytes." }
        return bytes.asList().chunked(Frame.payloadSize).map { fragment ->
            ByteArray(Frame.bodySize).also { report ->
                report[0] = Frame.channel.toByte()
                report[1] = fragment.size.toByte()
                fragment.forEachIndexed { index, value -> report[index + 2] = value }
            }
        }
    }
}

private sealed interface Boundary {
    data object Incomplete : Boundary
    data class Complete(val end: Int) : Boundary
    data class Invalid(val reason: String) : Boundary
}

private fun boundary(bytes: ByteArray): Boundary {
    if (bytes.isEmpty()) return Boundary.Incomplete
    if (bytes[0] != '{'.code.toByte()) return Boundary.Invalid("RPC payload must begin with an object.")

    var depth = 0
    var quoted = false
    var escaped = false
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 0xff
        if (quoted) {
            when {
                escaped -> escaped = false
                value == '\\'.code -> escaped = true
                value == '"'.code -> quoted = false
            }
            continue
        }
        when (value) {
            '"'.code -> quoted = true
            '{'.code, '['.code -> depth += 1
            '}'.code, ']'.code -> {
                depth -= 1
                if (depth < 0) return Boundary.Invalid("RPC payload has unbalanced delimiters.")
                if (depth == 0) return Boundary.Complete(index + 1)
            }
        }
    }
    return Boundary.Incomplete
}

private fun ByteArray.decodeUtf8(): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(this)).toString()
}
