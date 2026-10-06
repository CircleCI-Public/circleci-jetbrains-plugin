package com.circleci.idea.job

import com.circleci.idea.api.clients.StepOutputChunk
import com.circleci.idea.state.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream

/** Something that happened while streaming a step's output. */
sealed class StepOutputEvent {
    /** Complete lines of stdout, decoded. */
    data class Stdout(val text: String) : StepOutputEvent()

    /** The step's stderr, read once stdout finished. */
    data class Stderr(val text: String) : StepOutputEvent()

    /** The first [bytes] of the stdout or stderr that follows weren't read, to show only its end. */
    data class Skipped(val bytes: Long) : StepOutputEvent()

    data class Failed(val error: Throwable) : StepOutputEvent()
}

/**
 * Streams a step's output the way `circleci run get` does: read stdout from
 * the current byte offset, then every [pollIntervalMs] read on from where it
 * left off, until the server says stdout is complete (X-Terminal); then read
 * stderr once. A read that stopped at its limit is followed by the next
 * straight away, once the output is showing.
 *
 * Only the last [tailBytes] of each is read, as the console keeps no more
 * than about that: a longer log, by the size the [step] last reported,
 * starts at the first whole line after that point.
 *
 * A step that has ended without stdout ever being marked complete (one that
 * produced none, say) would otherwise be polled forever, so once the [step]
 * has ended, a read that brings nothing new ends the stream too.
 *
 * Output is emitted a line at a time, so a chunk boundary never splits a
 * UTF-8 character or an ANSI escape sequence.
 *
 * @param step The step as last fetched, if it has been, for its status and output sizes
 * @param awaitShowing Returns once the output is on screen, for a running step's to wait while it's not
 */
class StepOutputStream(
    private val fetchStdout: suspend (offset: Long) -> Result<StepOutputChunk>,
    private val fetchStderr: suspend (offset: Long) -> Result<ByteArray>,
    private val step: () -> Step?,
    private val awaitShowing: suspend () -> Unit = {},
    private val tailBytes: Long = TAIL_BYTES,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    fun events(): Flow<StepOutputEvent> =
        flow {
            // A missing stderr is no reason to hide the stdout already shown.
            if (emitStdout()) emitStderr()
        }

    /** Emit stdout until it's complete, giving whether it was read. */
    private suspend fun FlowCollector<StepOutputEvent>.emitStdout(): Boolean {
        val lines = LineBuffer()
        var offset = tailOffset(step()?.stdoutBytes)
        if (offset > 0) {
            emit(StepOutputEvent.Skipped(offset))
            lines.skipPartialLine()
        }
        while (true) {
            val result = fetchStdout(offset)
            val chunk = result.getOrNull()
            if (chunk == null) {
                emit(StepOutputEvent.Failed(result.exceptionOrNull() ?: IllegalStateException("No output")))
                return false
            }

            offset += chunk.data.size
            lines.append(chunk.data)?.let { emit(StepOutputEvent.Stdout(it)) }

            if (!chunk.more) {
                if (chunk.terminal || (chunk.data.isEmpty() && step()?.status?.isActive != true)) break
                delay(pollIntervalMs)
            }
            awaitShowing()
        }
        lines.flush()?.let { emit(StepOutputEvent.Stdout(it)) }
        return true
    }

    private suspend fun FlowCollector<StepOutputEvent>.emitStderr() {
        val offset = tailOffset(step()?.stderrBytes)
        val bytes = fetchStderr(offset).getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        if (offset > 0) emit(StepOutputEvent.Skipped(offset))
        val lines = LineBuffer().apply { if (offset > 0) skipPartialLine() }
        val text = listOfNotNull(lines.append(bytes), lines.flush()).joinToString("")
        if (text.isNotEmpty()) emit(StepOutputEvent.Stderr(text))
    }

    /** Where to start reading output of [size] bytes, to read only its last [tailBytes]. */
    private fun tailOffset(size: Long?): Long = maxOf(0L, (size ?: 0L) - tailBytes)

    /**
     * Holds back a trailing partial line until the rest of it arrives.
     * "\n" never occurs inside a UTF-8 multi-byte character, so splitting
     * there always leaves whole characters.
     */
    internal class LineBuffer {
        private val pending = ByteArrayOutputStream()
        private var skipping = false

        /** Drop what's added up to the first line break, for output read from part way through a line. */
        fun skipPartialLine() {
            skipping = true
        }

        /** Add bytes; returns the complete lines now available, or null for none. */
        fun append(bytes: ByteArray): String? {
            val data = afterSkipped(bytes)
            if (data.isEmpty()) return null
            val lastNewline = data.lastIndexOf(NEWLINE)
            if (lastNewline < 0) {
                pending.write(data)
                return null
            }
            pending.write(data, 0, lastNewline + 1)
            val text = pending.toString(Charsets.UTF_8)
            pending.reset()
            pending.write(data, lastNewline + 1, data.size - lastNewline - 1)
            return normalize(text)
        }

        /** [bytes] without what's still to skip of a partial line. */
        private fun afterSkipped(bytes: ByteArray): ByteArray {
            if (!skipping) return bytes
            val lineEnd = bytes.indexOf(NEWLINE)
            if (lineEnd < 0) return ByteArray(0)
            skipping = false
            return bytes.copyOfRange(lineEnd + 1, bytes.size)
        }

        /** Whatever partial line remains, once no more is coming. */
        fun flush(): String? {
            if (pending.size() == 0) return null
            return normalize(pending.toString(Charsets.UTF_8)).also { pending.reset() }
        }
    }

    companion object {
        /** How often a running step's output is re-read, as in the CLI. */
        const val POLL_INTERVAL_MS = 2_000L

        /** How much of the end of a step's stdout, and of its stderr, is read: about what the console holds. */
        const val TAIL_BYTES = 1024 * 1024L

        private const val NEWLINE = '\n'.code.toByte()

        /** CircleCI output uses CRLF line endings; the console wants "\n". */
        internal fun normalize(text: String): String = text.replace("\r\n", "\n")
    }
}
