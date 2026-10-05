package com.circleci.idea.job

import com.circleci.idea.api.clients.StepOutputChunk
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

    private fun stream(
        stdout: FakeStdout,
        stderr: String = "",
        isStepActive: () -> Boolean = { true },
    ) = StepOutputStream(
        fetchStdout = stdout::fetch,
        fetchStderr = { Result.success(stderr.toByteArray()) },
        isStepActive = isStepActive,
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
            val events = stream(stdout, isStepActive = { false }).events().toList()

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
                    isStepActive = { false },
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
                    isStepActive = { true },
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
                    isStepActive = { true },
                    pollIntervalMs = 0,
                )
            val events = stream.events().toList()

            assertEquals("one event", 1, events.size)
            assertTrue("it's the failure", (events.single() as StepOutputEvent.Failed).error.message == "boom")
        }

    @Test
    fun testLineBufferKeepsMultiByteCharactersWhole() {
        val buffer = StepOutputStream.LineBuffer()
        val bytes = "é\n".toByteArray()

        assertEquals("half a character is held back", null, buffer.append(bytes.copyOfRange(0, 1)))
        assertEquals("and completed by the next read", "é\n", buffer.append(bytes.copyOfRange(1, bytes.size)))
    }
}
