// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.termux

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.DeviceToolNames
import com.droidance.core.device.tools.UnifiedDeviceToolRegistrar
import com.droidance.core.agent.tools.ToolDefinition
import com.droidance.core.agent.tools.ToolParameter
import com.droidance.core.agent.tools.ToolTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermuxToolRegistrar @Inject constructor(
    private val bootstrapManager: TermuxBootstrapManager,
    private val packageManager: TermuxPackageManager,
    private val prootLauncher: TermuxProotLauncher,
    private val registerTool: (UnifiedDeviceToolRegistrar.Tool) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun registerAll() {
        registerTool(TermuxActTool())
    }

    private inner class TermuxActTool : UnifiedDeviceToolRegistrar.Tool() {
        override val name = DeviceToolNames.TERMUX_ACT
        override val tier = ToolTier.DEVICE

        override val definition = ToolDefinition(
            name = name,
            description = "Termux environment management: ensure_ready, exec, install, remove, search, list, copy_to, copy_from, session_start, session_send, session_close",
            parameters = mapOf(
                "op" to ToolParameter("string", "Operation", true, listOf(
                    "ensure_ready", "exec", "install", "remove", "search",
                    "list_installed", "copy_to_termux", "copy_from_termux",
                    "session_start", "session_send", "session_close"
                )),
                "command" to ToolParameter("string", "Command to execute (for exec)", false),
                "working_dir" to ToolParameter("string", "Working directory", false),
                "timeout_ms" to ToolParameter("integer", "Timeout in milliseconds", false),
                "package_name" to ToolParameter("string", "Package name (for install/remove/search)", false),
                "assume_yes" to ToolParameter("boolean", "Assume yes for installs", false),
                "host_source" to ToolParameter("string", "Host file path (for copy_to_termux)", false),
                "termux_destination" to ToolParameter("string", "Termux destination path (for copy_to_termux)", false),
                "termux_source" to ToolParameter("string", "Termux source path (for copy_from_termux)", false),
                "host_destination" to ToolParameter("string", "Host destination path (for copy_from_termux)", false),
                "session_id" to ToolParameter("integer", "Session ID (for session_send/close)", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult = withContext(Dispatchers.IO) {
            val input = json.decodeFromString<JsonObject>(arguments)
            val op = input["op"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("op")

            return when (op) {
                "ensure_ready" -> ensureReady()
                "exec" -> exec(input)
                "install" -> install(input)
                "remove" -> remove(input)
                "search" -> search(input)
                "list_installed" -> listInstalled(input)
                "copy_to_termux" -> copyToTermux(input)
                "copy_from_termux" -> copyFromTermux(input)
                "session_start" -> sessionStart(input)
                "session_send" -> sessionSend(input)
                "session_close" -> sessionClose(input)
                else -> ToolProtocolResult.notExecuted("UNKNOWN_TERMUX_OP: $op", true)
            }
        }

        private fun ensureReady(): ToolExecutionResult {
            val result = bootstrapManager.ensureBootstrapReady().getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("status", "ready")
                put("rootfs_path", result.rootfsPath)
                put("version", result.version)
                put("sha256", result.sha256)
            })
        }

        private fun exec(input: JsonObject): ToolExecutionResult {
            val command = input["command"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("command")
            val workingDir = input["working_dir"]?.jsonPrimitive?.content ?: "/data/data/com.droidance.fork/files/termux-rootfs/home"
            val timeoutMs = (input["timeout_ms"]?.jsonPrimitive?.long ?: 120_000L)

            val result = prootLauncher.executeCommand(command, workingDir, timeoutMs = timeoutMs).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("exit_code", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timed_out", result.timedOut)
            })
        }

        private fun install(input: JsonObject): ToolExecutionResult {
            val packageName = input["package_name"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("package_name")
            val assumeYes = input["assume_yes"]?.jsonPrimitive?.boolean ?: true
            packageManager.installPackage(packageName, assumeYes).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("package", packageName)
                put("installed", true)
            })
        }

        private fun remove(input: JsonObject): ToolExecutionResult {
            val packageName = input["package_name"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("package_name")
            packageManager.removePackage(packageName).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("package", packageName)
                put("removed", true)
            })
        }

        private fun search(input: JsonObject): ToolExecutionResult {
            val query = input["package_name"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("package_name")
            val packages = packageManager.searchPackages(query).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("packages", packages.map { buildJsonObject {
                    put("name", it.name)
                    put("version", it.version)
                    put("description", it.description)
                    put("installed", it.installed)
                } })
            })
        }

        private fun listInstalled(input: JsonObject): ToolExecutionResult {
            val packages = packageManager.listInstalledPackages().getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("packages", packages.map { buildJsonObject {
                    put("name", it.name)
                    put("version", it.version)
                    put("installed", it.installed)
                } })
            })
        }

        private fun copyToTermux(input: JsonObject): ToolExecutionResult {
            val hostSource = input["host_source"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("host_source")
            val termuxDest = input["termux_destination"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("termux_destination")
            val hostFile = java.io.File(hostSource)
            bootstrapManager.copyToTermux(hostFile, termuxDest).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject { put("copied", true) })
        }

        private fun copyFromTermux(input: JsonObject): ToolExecutionResult {
            val termuxSource = input["termux_source"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("termux_source")
            val hostDest = input["host_destination"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("host_destination")
            val hostFile = java.io.File(hostDest)
            bootstrapManager.copyFromTermux(termuxSource, hostFile).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject { put("copied", true) })
        }

        private fun sessionStart(input: JsonObject): ToolExecutionResult {
            val initialCommand = input["command"]?.jsonPrimitive?.content
            val workingDir = input["working_dir"]?.jsonPrimitive?.content ?: "/data/data/com.droidance.fork/files/termux-rootfs/home"
            val session = prootLauncher.startInteractiveSession(initialCommand, workingDir).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("session_id", session.pid)
                put("working_dir", session.workingDir)
            })
        }

        private fun sessionSend(input: JsonObject): ToolExecutionResult {
            val sessionId = input["session_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("session_id")
            val command = input["command"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("command")
            val output = prootLauncher.sendToSession(sessionId, command).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("session_id", sessionId)
                put("output", output)
            })
        }

        private fun sessionClose(input: JsonObject): ToolExecutionResult {
            val sessionId = input["session_id"]?.jsonPrimitive?.int ?: return ToolProtocolResult.missingArgument("session_id")
            prootLauncher.closeSession(sessionId)
            return ToolProtocolResult.success(buildJsonObject {
                put("session_id", sessionId)
                put("closed", true)
            })
        }
    }
}