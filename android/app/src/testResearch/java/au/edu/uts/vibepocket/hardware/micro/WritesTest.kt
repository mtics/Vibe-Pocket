package au.edu.uts.vibepocket.hardware.micro

import au.edu.uts.vibepocket.hardware.micro.protocol.Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritesTest {
    private val connection = Connection(7, "host")
    private val output = "output"

    @Test
    fun fragmentsEchoAndExecuteProducesOneNormalizedBody() {
        val writes = Writes<String, String>()
        val report = byteArrayOf(Frame.reportId.toByte()) + body()

        val first = writes.stage(
            connection, output, output, 0, report.copyOfRange(0, 20), 0,
        ) as Stage.Echo
        assertEquals(0, first.offset)
        assertArrayEquals(report.copyOfRange(0, 20), first.value)

        val second = writes.stage(
            connection, output, output, 20, report.copyOfRange(20, report.size), 1,
        ) as Stage.Echo
        assertEquals(20, second.offset)
        assertArrayEquals(report.copyOfRange(20, report.size), second.value)

        val ready = writes.execute(connection, execute = true) as Execution.Ready
        assertArrayEquals(body(), ready.body)
        assertFalse(writes.active())
    }

    @Test
    fun nonOverlappingFragmentsMayArriveOutOfOrder() {
        val writes = Writes<String, String>()
        val report = byteArrayOf(Frame.reportId.toByte()) + body()
        val split = 29

        writes.stage(
            connection,
            output,
            output,
            split,
            report.copyOfRange(split, report.size),
            0,
        )
        writes.stage(
            connection,
            output,
            output,
            0,
            report.copyOfRange(0, split),
            1,
        )

        val ready = writes.execute(connection, execute = true) as Execution.Ready
        assertArrayEquals(body(), ready.body)
    }

    @Test
    fun transactionIsDeviceLevelAndAnyCrossTargetFragmentPoisonsExecute() {
        val writes = Writes<String, String>()
        writes.stage(connection, output, output, 0, body().copyOfRange(0, 20), 0)

        assertEquals(
            Stage.Rejected(WriteFault.TARGET),
            writes.stage(connection, "descriptor", output, 20, ByteArray(2), 1),
        )
        assertEquals(Execution.Rejected(WriteFault.POISONED), writes.execute(connection, true))
        assertFalse(writes.active())
    }

    @Test
    fun overlapBoundaryFragmentLimitAndConnectionChangePoison() {
        val overlap = Writes<String, String>()
        overlap.stage(connection, output, output, 0, ByteArray(2), 0)
        assertEquals(
            Stage.Rejected(WriteFault.OVERLAP),
            overlap.stage(connection, output, output, 1, ByteArray(1), 1),
        )

        val boundary = Writes<String, String>()
        assertEquals(
            Stage.Rejected(WriteFault.BOUNDARY),
            boundary.stage(connection, output, output, 64, ByteArray(1), 0),
        )

        val limited = Writes<String, String>(maxFragments = 1)
        limited.stage(connection, output, output, 0, ByteArray(1), 0)
        assertEquals(
            Stage.Rejected(WriteFault.LIMIT),
            limited.stage(connection, output, output, 1, ByteArray(1), 1),
        )

        val changed = Writes<String, String>()
        changed.stage(connection, output, output, 0, ByteArray(1), 0)
        assertEquals(
            Stage.Rejected(WriteFault.TARGET),
            changed.stage(Connection(8, "host"), output, output, 1, ByteArray(1), 1),
        )
    }

    @Test
    fun emptyExecuteAndCancelAreSuccessfulAndHaveNoBody() {
        val writes = Writes<String, String>()
        assertEquals(Execution.Empty, writes.execute(connection, execute = true))
        writes.stage(connection, output, output, 0, ByteArray(1), 0)
        assertEquals(Execution.Cancelled, writes.execute(connection, execute = false))
        assertFalse(writes.active())
    }

    @Test
    fun holesAndInvalidReportIdRejectWithoutDecoderInput() {
        val hole = Writes<String, String>()
        hole.stage(connection, output, output, 1, ByteArray(62), 0)
        assertEquals(Execution.Rejected(WriteFault.INCOMPLETE), hole.execute(connection, true))

        val reportId = Writes<String, String>()
        reportId.stage(connection, output, output, 0, byteArrayOf(5) + body(), 0)
        assertEquals(Execution.Rejected(WriteFault.BOUNDARY), reportId.execute(connection, true))
    }

    @Test
    fun timeoutClearsTheWholeTransaction() {
        val writes = Writes<String, String>(timeoutMs = 10)
        writes.stage(connection, output, output, 0, ByteArray(1), 5)

        assertFalse(writes.expire(14))
        assertTrue(writes.expire(15))
        assertEquals(Execution.Empty, writes.execute(connection, true))
    }

    @Test
    fun secondaryHostDisconnectCannotClearActiveHostTransaction() {
        val writes = Writes<String, String>()
        val report = byteArrayOf(Frame.reportId.toByte()) + body()
        writes.stage(connection, output, output, 0, report, 0)

        assertFalse(writes.clear(Connection(connection.generation, "secondary")))
        assertTrue(writes.active())
        assertTrue(writes.clear(connection))
        assertFalse(writes.active())
    }

    @Test
    fun secondaryHostWriteCannotPoisonActiveHostTransaction() {
        val writes = Writes<String, String>()
        val report = byteArrayOf(Frame.reportId.toByte()) + body()
        writes.stage(connection, output, output, 0, report, 0)
        var rejected = false

        if (acceptOwner(ownsDevice = false) { rejected = true }) {
            writes.poison(WriteFault.TARGET)
        }

        assertTrue(rejected)
        assertTrue(writes.execute(connection, execute = true) is Execution.Ready)
    }

    @Test
    fun secondaryHostExecuteCannotConsumeActiveHostTransaction() {
        val writes = Writes<String, String>()
        val report = byteArrayOf(Frame.reportId.toByte()) + body()
        writes.stage(connection, output, output, 0, report, 0)
        var rejected = false

        if (acceptOwner(ownsDevice = false) { rejected = true }) {
            writes.execute(Connection(connection.generation, "secondary"), execute = true)
        }

        assertTrue(rejected)
        assertTrue(writes.execute(connection, execute = true) is Execution.Ready)
    }

    @Test
    fun inboxHasOneExplicitCommitBoundary() {
        val inbox = Inbox()
        assertTrue(inbox.stage(body()))
        assertFalse(inbox.stage(body()))
        assertTrue(inbox.pending())
        assertArrayEquals(body(), inbox.release())
        assertFalse(inbox.pending())
    }

    private fun body(): ByteArray = ByteArray(Frame.bodySize).also {
        it[0] = Frame.channel.toByte()
    }
}
