package com.circleci.idea.job

import com.circleci.idea.api.clients.StepOutputChunk
import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Step
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepOutputStreamTest {
    /** Serves stdout as a sequence of reads, recording each offset asked for. */
    private class FakeStdout(private val reads: List<Pair<String, Boolean>>) {
        val offsets = mutableListOf<Long>()
        private var next = 0

        fun fetch(offset: Long): Result<StepOutputChunk> {
            offsets.add(offset)
            val (text, terminal) = reads.getOrElse(next++) { "" to false }
            return Result.success(StepOutputChunk(text.toByteArray(), terminal))
        }
    }

    private fun step(
        active: Boolean,
        stdoutBytes: Long? = null,
        stderrBytes: Long? = null,
    ) = Step(
        num = 1,
        name = "Run tests",
        type = "run",
        status = if (active) RunStatus.RUNNING else RunStatus.SUCCESS,
        exitCode = null,
        startedAt = null,
        endedAt = null,
        stdoutBytes = stdoutBytes,
        stderrBytes = stderrBytes,
    )

    private fun stream(
        stdout: FakeStdout,
        stderr: String = "",
        active: Boolean = true,
    ) = StepOutputStream(
        fetchStdout = stdout::fetch,
        fetchStderr = { Result.success(stderr.toByteArray()) },
        step = { step(active) },
        pollIntervalMs = 0,
    )

    @Test
    fun testReadsFromOffsetUntilTerminalThenStderr() =
        runBlocking {
            val stdout = FakeStdout(listOf("one\r\ntw" to false, "o\r\n" to false, "three\r\n" to true))
            val events = stream(stdout, stderr = "oops\r\n").events().toList()

            assertEquals("each read continues from the last", listOf(0L, 7L, 10L), stdout.offsets)
            assertEquals(
                "lines are emitted whole, CRLF normalized, stderr last",
                listOf(
                    StepOutputEvent.Stdout("one\n"),
                    StepOutputEvent.Stdout("two\n"),
                    StepOutputEvent.Stdout("three\n"),
                    StepOutputEvent.Stderr("oops\n"),
                ),
                events,
            )
        }

    @Test
    fun testEndedStepWithoutTerminalStopsOnEmptyRead() =
        runBlocking {
            val stdout = FakeStdout(listOf("partial" to false))
            val events = stream(stdout, active = false).events().toList()

            assertEquals("stops after the read that brings nothing", listOf(0L, 7L), stdout.offsets)
            assertEquals("the trailing partial line is flushed", listOf(StepOutputEvent.Stdout("partial")), events)
        }

    @Test
    fun testReadAtItsLimitIsFollowedByTheRest() =
        runBlocking {
            // Terminal from the first read, as the step had finished, but that read stopped at its limit.
            val reads =
                listOf(
                    StepOutputChunk("one\n".toByteArray(), true, more = true),
                    StepOutputChunk("two\n".toByteArray(), true),
                )
            val offsets = mutableListOf<Long>()
            val stream =
                StepOutputStream(
                    fetchStdout = { offset -> Result.success(reads[offsets.size].also { offsets.add(offset) }) },
                    fetchStderr = { Result.success(ByteArray(0)) },
                    step = { step(active = false) },
                    pollIntervalMs = 0,
                )
            val events = stream.events().toList()

            assertEquals("reads on from the limit", listOf(0L, 4L), offsets)
            assertEquals(
                "both reads' lines",
                listOf(StepOutputEvent.Stdout("one\n"), StepOutputEvent.Stdout("two\n")),
                events,
            )
        }

    @Test
    fun testWaitsToBeShownBeforeEachPoll() =
        runBlocking {
            val stdout = FakeStdout(listOf("one\n" to false, "two\n" to true))
            var waits = 0
            val stream =
                StepOutputStream(
                    fetchStdout = stdout::fetch,
                    fetchStderr = { Result.success(ByteArray(0)) },
                    step = { step(active = true) },
                    awaitShowing = { waits++ },
                    pollIntervalMs = 0,
                )
            stream.events().toList()

            assertEquals("waited before the second read only", 1, waits)
        }

    @Test
    fun testFailedReadEndsStream() =
        runBlocking {
            val stream =
                StepOutputStream(
                    fetchStdout = { Result.failure(IllegalStateException("boom")) },
                    fetchStderr = { error("stderr shouldn't be read") },
                    step = { step(active = true) },
                    pollIntervalMs = 0,
                )
            val events = stream.events().toList()

            assertEquals("one event", 1, events.size)
            assertTrue("it's the failure", (events.single() as StepOutputEvent.Failed).error.message == "boom")
        }

    @Test
    fun testLongOutputIsReadFromNearItsEnd() =
        runBlocking {
            val stdout = FakeStdout(listOf("ial\nend\n" to true))
            val stderrOffsets = mutableListOf<Long>()
            val stream =
                StepOutputStream(
                    fetchStdout = stdout::fetch,
                    fetchStderr = { offset ->
                        stderrOffsets.add(offset)
                        Result.success("rr\nlast\n".toByteArray())
                    },
                    step = { step(active = false, stdoutBytes = 108, stderrBytes = 1008) },
                    tailBytes = 8,
                    pollIntervalMs = 0,
                )
            val events = stream.events().toList()

            assertEquals("stdout from its last 8 bytes", 100L, stdout.offsets.first())
            assertEquals("stderr too", listOf(1000L), stderrOffsets)
            assertEquals(
                "what's skipped, then the whole lines after",
                listOf(
                    StepOutputEvent.Skipped(100),
                    StepOutputEvent.Stdout("end\n"),
                    StepOutputEvent.Skipped(1000),
                    StepOutputEvent.Stderr("last\n"),
                ),
                events,
            )
        }

    @Test
    fun testWaitsToBeShownBeforeReadingOnFromALimit() =
        runBlocking {
            val reads =
                listOf(
                    StepOutputChunk("one\n".toByteArray(), true, more = true),
                    StepOutputChunk("two\n".toByteArray(), true),
                )
            var read = 0
            var waits = 0
            StepOutputStream(
                fetchStdout = { Result.success(reads[read++]) },
                fetchStderr = { Result.success(ByteArray(0)) },
                step = { step(active = false) },
                awaitShowing = { waits++ },
                pollIntervalMs = 0,
            ).events().toList()

            assertEquals("waited before the read after the limit", 1, waits)
        }

    @Test
    fun testLineBufferKeepsMultiByteCharactersWhole() {
        val buffer = StepOutputStream.LineBuffer()
        val bytes = "é\n".toByteArray()

        assertEquals("half a character is held back", null, buffer.append(bytes.copyOfRange(0, 1)))
        assertEquals("and completed by the next read", "é\n", buffer.append(bytes.copyOfRange(1, bytes.size)))
    }
}
