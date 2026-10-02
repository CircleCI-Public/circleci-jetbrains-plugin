package com.circleci.idea.ssh

/**
 * Where to point `ssh` at, as printed by a job's "Enable SSH" step. Two
 * formats are in the wild, and both have to keep working:
 *
 * - [Proxy], the default: ssh.circleci.com fronts the job, and the
 *   *username* names the session (`<job-id>-<execution>`), so it isn't ours
 *   to choose. It runs the job's own shell, so needs no command.
 * - [Direct]: the job's machine is reachable on its own address and a high
 *   port, and the username is ignored. This is what runner and server print,
 *   and what cloud printed before ssh.circleci.com.
 */
sealed class SshEndpoint {
    abstract val host: String

    /** The port, or null for SSH's default. */
    abstract val port: Int?

    /** The command to connect from a terminal, as the step printed it. */
    abstract val command: String

    data class Proxy(override val host: String, override val port: Int?, val user: String) : SshEndpoint() {
        override val command: String
            get() = "ssh " + (port?.let { "-p $it " } ?: "") + "$user@$host"
    }

    data class Direct(override val host: String, override val port: Int) : SshEndpoint() {
        override val command: String
            get() = "ssh -p $port $host"
    }

    companion object {
        // `ssh [-p N] user@host`. Only the proxy prints a user@host straight
        // after `ssh ` (or its port), so a stray address elsewhere in the
        // output can't be taken for one. `-p` isn't printed today, but the
        // proxy also answers on 443, so a port is accepted rather than assumed away.
        private val PROXY = Regex("""\bssh (?:-p (\d+) )?([A-Za-z0-9][A-Za-z0-9._-]*)@([A-Za-z0-9][A-Za-z0-9.-]*)""")

        // `ssh [-t] -p N a.b.c.d`, with Windows adding `-- bash.exe` and the like after.
        private val DIRECT = Regex("""\bssh (?:-t )?-p (\d+) (\d{1,3}(?:\.\d{1,3}){3})\b""")

        /**
         * Read the endpoint out of an "Enable SSH" step's output, e.g.
         *
         *     You can now SSH into this job if your SSH public key is added:
         *         $ ssh 057f0065-7e6d-4912-921b-50917ff046a3-0@ssh.circleci.com
         *
         * or, directly (Windows prints one line per shell; the first is taken):
         *
         *     You can now SSH into this box if your SSH public key is added:
         *     $ ssh -p 64535 3.239.179.134
         *
         * Null when the output holds neither.
         */
        fun parse(output: String): SshEndpoint? {
            PROXY.find(output)?.let { match ->
                val (port, user, host) = match.destructured
                return Proxy(host, port.toIntOrNull(), user)
            }
            DIRECT.find(output)?.let { match ->
                val (port, host) = match.destructured
                return Direct(host, port.toInt())
            }
            return null
        }
    }
}
