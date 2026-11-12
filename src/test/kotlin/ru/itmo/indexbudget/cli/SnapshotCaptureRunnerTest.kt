package ru.itmo.indexbudget.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.indexbudget.application.ShutdownCancellation
import ru.itmo.indexbudget.statistics.SnapshotLimits
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Lifecycle/publication guarantees shared by both statistics commands. No JDBC or format policy. */
class SnapshotCaptureRunnerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `single capture publishes once without requiring an interval or waiting`() {
        val output = directory.resolve("single")
        var calls = 0
        val runner = SnapshotCaptureRunner(ShutdownCancellation { AutoCloseable {} }) {
            error("Single capture must not wait")
        }
        val code = runner.execute(
            requestFactory = { CaptureRequest(UUID.randomUUID(), output, null, SnapshotLimits()) },
            collect = { _, _ -> ++calls },
            render = { "$it".toByteArray() },
            isComplete = { true },
            completionStatus = { "completed" },
            manifest = { saved, status, error, interval -> "$status:$error:$interval:${saved.size}".toByteArray() },
            out = PrintWriter(StringWriter()),
            err = PrintWriter(StringWriter()),
        )
        assertEquals(0, code)
        assertEquals(1, calls)
        assertEquals("1", Files.readString(output.resolve("snapshot-1.json")))
        assertFalse(Files.exists(output.resolve("snapshot-2.json")))
        assertEquals("completed:null:null:1", Files.readString(output.resolve("capture.json")))
    }

    @Test fun `cancellation while waiting preserves the first file and unregisters the hook`() {
        var cancel: () -> Unit = {}
        var unregistered = false
        var calls = 0
        var collectorFinished = false
        val shutdown = ShutdownCancellation { callback ->
            cancel = callback
            AutoCloseable { unregistered = true }
        }
        val runner = SnapshotCaptureRunner(shutdown) {
            assertTrue(collectorFinished, "Wait starts only after collection returned and released its resources")
            cancel()
        }
        val output = directory.resolve("cancelled")
        val code = runner.execute(
            requestFactory = { CaptureRequest(UUID.randomUUID(), output, 1, SnapshotLimits()) },
            collect = { _, _ -> calls++; collectorFinished = true; 1 }, render = { "$it".toByteArray() },
            isComplete = { true }, completionStatus = { "completed" },
            manifest = { saved, status, error, _ -> "$status:$error:${saved.size}".toByteArray() },
            out = PrintWriter(StringWriter()), err = PrintWriter(StringWriter()),
        )
        assertEquals(3, code)
        assertEquals(1, calls)
        assertEquals("1", Files.readString(output.resolve("snapshot-1.json")))
        assertFalse(Files.exists(output.resolve("snapshot-2.json")))
        assertEquals("partial:CANCELLED:1", Files.readString(output.resolve("capture.json")))
        assertTrue(unregistered)
    }

    @Test fun `failed second publication cleans staging and preserves first snapshot and foreign destination`() {
        val output = directory.resolve("publication")
        var calls = 0
        val err = StringWriter()
        val runner = SnapshotCaptureRunner(ShutdownCancellation { AutoCloseable {} }, Thread::sleep)
        val code = runner.execute(
            requestFactory = { CaptureRequest(UUID.randomUUID(), output, 1, SnapshotLimits()) },
            collect = { _, _ -> ++calls }, render = { index ->
                if (index == 2) {
                    Files.createDirectory(output.resolve("snapshot-2.json"))
                    Files.writeString(output.resolve("snapshot-2.json/foreign.txt"), "keep")
                }
                "snapshot-$index".toByteArray()
            }, isComplete = { true }, completionStatus = { "completed" },
            manifest = { saved, status, error, _ -> "$status:$error:${saved.size}".toByteArray() },
            out = PrintWriter(StringWriter()), err = PrintWriter(err),
        )
        assertEquals(3, code)
        assertEquals("snapshot-1", Files.readString(output.resolve("snapshot-1.json")))
        assertEquals("keep", Files.readString(output.resolve("snapshot-2.json/foreign.txt")))
        assertEquals("partial:EXPORT_FAILED:1", Files.readString(output.resolve("capture.json")))
        assertTrue(Files.list(output).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        assertTrue(err.toString().contains("EXPORT_FAILED"))
    }

    @Test fun `second collection failure publishes only the first and hides raw exception text`() {
        val output = directory.resolve("read-error")
        var calls = 0
        val err = StringWriter()
        val code = SnapshotCaptureRunner(ShutdownCancellation { AutoCloseable {} }, Thread::sleep).execute(
            requestFactory = { CaptureRequest(UUID.randomUUID(), output, 1, SnapshotLimits()) },
            collect = { _, _ -> if (++calls == 2) error("DSN secret-password") else 1 },
            render = { "$it".toByteArray() }, isComplete = { true }, completionStatus = { "completed" },
            manifest = { saved, status, error, _ -> "$status:$error:${saved.size}".toByteArray() },
            out = PrintWriter(StringWriter()), err = PrintWriter(err),
        )
        assertEquals(3, code)
        assertEquals("partial:STATISTICS_READ_FAILED:1", Files.readString(output.resolve("capture.json")))
        assertFalse(err.toString().contains("secret-password"))
        assertTrue(Files.exists(output.resolve("snapshot-1.json")))
        assertFalse(Files.exists(output.resolve("snapshot-2.json")))
    }
}
