package com.circleci.idea.job

import com.circleci.idea.api.clients.StepOutputChunk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream

/** Something that happened while streaming a step's output. */
sealed class StepOutputEvent {
    /** Complete lines of stdout, decoded. */
    data class Stdout(val text: String) : StepOutputEvent()

    /** The step's stderr, read once stdout finished. */
    data class Stderr(val text: String) : StepOutputEvent()

    data class Failed(val error: Throwable) : StepOutputEvent()
}

/**
 * Streams a step's output the way `circleci run get` does: read stdout from
 * the current byte offset, then every [pollIntervalMs] read on from where it
 * left off, until the server says stdout is complete (X-Terminal); then read
 * stderr once. A read that stopped at its limit is followed by the next
 * straight away.
 *
 * A step that has ended without stdout ever being marked complete (one that
 * produced none, say) would otherwise be polled forever, so once
 * [isStepActive] says it has ended, a read that brings nothing new ends the
 * stream too.
 *
 * Output is emitted a line at a time, so a chunk boundary never splits a
 * UTF-8 character or an ANSI escape sequence.
 */
class StepOutputStream(
    private val fetchStdout: suspend (offset: Long) -> Result<StepOutputChunk>,
    private val fetchStderr: suspend () -> Result<ByteArray>,
    private val isStepActive: () -> Boolean,
    // Returns once the output is on screen, for a running step's to wait while it's not.
    private val awaitShowing: suspend () -> Unit = {},
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    fun events(): Flow<StepOutputEvent> =
        flow {
            val lines = LineBuffer()
            var offset = 0L
            while (true) {
                val result = fetchStdout(offset)
                val chunk = result.getOrNull()
                if (chunk == null) {
                    emit(StepOutputEvent.Failed(result.exceptionOrNull() ?: IllegalStateException("No output")))
                    return@flow
                }

                offset += chunk.data.size
                lines.append(chunk.data)?.let { emit(StepOutputEvent.Stdout(it)) }

                if (chunk.more) continue
                if (chunk.terminal || (chunk.data.isEmpty() && !isStepActive())) break
                delay(pollIntervalMs)
                awaitShowing()
            }
            lines.flush()?.let { emit(StepOutputEvent.Stdout(it)) }

            // A missing stderr is no reason to hide the stdout already shown.
            fetchStderr().getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                emit(StepOutputEvent.Stderr(normalize(String(it, Charsets.UTF_8))))
            }
        }

    /**
     * Holds back a trailing partial line until the rest of it arrives.
     * "\n" never occurs inside a UTF-8 multi-byte character, so splitting
     * there always leaves whole characters.
     */
    internal class LineBuffer {
        private val pending = ByteArrayOutputStream()

        /** Add bytes; returns the complete lines now available, or null for none. */
        fun append(data: ByteArray): String? {
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

        /** Whatever partial line remains, once no more is coming. */
        fun flush(): String? {
            if (pending.size() == 0) return null
            return normalize(pending.toString(Charsets.UTF_8)).also { pending.reset() }
        }
    }

    companion object {
        /** How often a running step's output is re-read, as in the CLI. */
        const val POLL_INTERVAL_MS = 2_000L

        private const val NEWLINE = '\n'.code.toByte()

        /** CircleCI output uses CRLF line endings; the console wants "\n". */
        internal fun normalize(text: String): String = text.replace("\r\n", "\n")
    }
}
