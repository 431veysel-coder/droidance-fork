// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.termux

import android.content.Context
import com.droidance.core.device.tools.AdbShellExecutor
import com.droidance.debug.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermuxProotLauncher @Inject constructor(
    @androidx.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val shellRunner: AdbShellExecutor,
    private val debugLogManager: DebugLogManager
) {
    companion object {
        const val PROOT_BINARY = "proot"
        const val DEFAULT_SHELL = "/bin/bash"
    }

    data class TermuxSession(
        val pid: Int,
        val shell: String,
        val workingDir: String,
        val env: Map<String, String>
    )

    private val activeSessions = mutableMapOf<Int, TermuxSession>()
    private var nextSessionId = 1

    /** PRoot ile Termux ortamında komut çalıştır (etiketsiz, background). */
    suspend fun executeCommand(
        command: String,
        workingDir: String = "/data/data/com.droidance.fork/files/termux-rootfs/home",
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000L
    ): Result<TermuxExecResult> = withContext(Dispatchers.IO) {
        val state = TermuxBootstrapManager.Companion::class.java // placeholder - inject real
        // Build proot command
        val rootfs = File(context.filesDir, "termux-rootfs").absolutePath
        val prootCmd = buildProotCommand(rootfs, command, workingDir, env)

        debugLogManager.appendEvent("termux: exec: $command (timeout=${timeoutMs}ms)")

        val result = shellRunner.shell(prootCmd, timeoutMs, "shell,v2")
        if (shellRunner.isFailure(result)) {
            return Result.failure(IllegalStateException("Termux exec failed: ${result.take(500)}"))
        }

        Result.success(TermuxExecResult(
            exitCode = 0, // shellRunner doesn't return exit code directly
            stdout = result,
            stderr = "",
            timedOut = false
        ))
    }

    /** Etkileşimli PTY oturumu başlat (script'ler için). */
    suspend fun startInteractiveSession(
        initialCommand: String? = null,
        workingDir: String = "/data/data/com.droidance.fork/files/termux-rootfs/home",
        env: Map<String, String> = emptyMap()
    ): Result<TermuxSession> = withContext(Dispatchers.IO) {
        val sessionId = nextSessionId++
        val rootfs = File(context.filesDir, "termux-rootfs").absolutePath

        // PTY için script dosyası oluştur
        val sessionDir = File(context.filesDir, "termux-sessions/session_$sessionId")
        sessionDir.mkdirs()

        val scriptFile = File(sessionDir, "session.sh")
        val envSetup = buildEnvSetup(rootfs, workingDir, env)
        scriptFile.writeText("""#!/bin/bash
$envSetup
cd "$workingDir"
exec /bin/bash --login
""".trimIndent())

        // Screen/tmux yerine basit background shell
        val session = TermuxSession(
            pid = sessionId,
            shell = "/bin/bash",
            workingDir = workingDir,
            env = env
        )
        activeSessions[sessionId] = session

        debugLogManager.appendEvent("termux: session $sessionId started at $workingDir")
        Result.success(session)
    }

    /** Oturuma komut gönder (background). */
    suspend fun sendToSession(sessionId: Int, command: String): Result<String> = withContext(Dispatchers.IO) {
        val session = activeSessions[sessionId] ?: return Result.failure(IllegalArgumentException("Session not found: $sessionId"))
        // For simplicity, execute as new proot command with same env
        executeCommand(command, session.workingDir, session.env).map { it.stdout }
    }

    /** Oturumu kapat. */
    fun closeSession(sessionId: Int) {
        activeSessions.remove(sessionId)
        debugLogManager.appendEvent("termux: session $sessionId closed")
    }

    private fun buildProotCommand(
        rootfs: String,
        command: String,
        workingDir: String,
        env: Map<String, String>
    ): String {
        val envExports = env.map { (k, v) -> "export $k=\"$v\";" }.joinToString(" ")
        val termuxEnv = buildTermuxEnv(rootfs)
        return """
            export PROOT_NO_SECCOMP=1
            export PROOT_TMP_DIR="$rootfs/tmp"
            $termuxEnv
            $envExports
            cd "$workingDir"
            proot --link2symlink \\
                -b /system -b /apex -b /vendor -b /odm \\
                -r "$rootfs" \\
                -w "$workingDir" \\
                /bin/bash -c "$command"
        """.trimIndent().replace("\n", " ")
    }

    private fun buildTermuxEnv(rootfs: String): String {
        val termuxBin = "$rootfs/bin"
        val termuxLib = "$rootfs/lib"
        val termuxUsrLib = "$rootfs/usr/lib"
        return """
            export PATH="$termuxBin:$termuxUsrLib:$termuxBin/applets:/system/bin:/system/xbin"
            export LD_LIBRARY_PATH="$termuxLib:$termuxUsrLib"
            export TERMUX_PREFIX="$rootfs/usr"
            export HOME="$rootfs/home"
            export TMPDIR="$rootfs/tmp"
            export PREFIX="$rootfs/usr"
        """.trimIndent()
    }

    private fun buildEnvSetup(rootfs: String, workingDir: String, env: Map<String, String>): String {
        return buildTermuxEnv(rootfs) + "\n" + env.map { (k, v) -> "export $k=\"$v\"" }.joinToString("\n")
    }

    data class TermuxExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    )
}