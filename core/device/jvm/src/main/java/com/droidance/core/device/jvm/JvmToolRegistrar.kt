// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.jvm

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
class JvmToolRegistrar @Inject constructor(
    private val jvmManager: JvmEmbeddedManager,
    private val registerTool: (UnifiedDeviceToolRegistrar.Tool) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun registerAll() {
        registerTool(JvmActTool())
    }

    private inner class JvmActTool : UnifiedDeviceToolRegistrar.Tool() {
        override val name = DeviceToolNames.JVM_ACT
        override val tier = ToolTier.DEVICE

        override val definition = ToolDefinition(
            name = name,
            description = "Compile and run Kotlin/Java code using embedded JVM: compile_run, gradle_build",
            parameters = mapOf(
                "op" to ToolParameter("string", "Operation", true, listOf("compile_run", "gradle_build", "ensure_ready")),
                "source_code" to ToolParameter("string", "Kotlin source code (for compile_run)", false),
                "main_class" to ToolParameter("string", "Main class name (default: MainKt)", false),
                "args" to ToolParameter("array", "Command line args", false, items = ToolParameter("string", "")),
                "project_dir" to ToolParameter("string", "Gradle project directory (for gradle_build)", false),
                "tasks" to ToolParameter("array", "Gradle tasks to run", false, items = ToolParameter("string", "")),
                "gradle_args" to ToolParameter("array", "Additional gradle args", false, items = ToolParameter("string", ""))
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult = withContext(Dispatchers.IO) {
            val input = json.decodeFromString<JsonObject>(arguments)
            val op = input["op"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("op")

            return when (op) {
                "ensure_ready" -> {
                    jvmManager.ensureJvmReady().getOrElse { throw it }
                    ToolProtocolResult.success(buildJsonObject { put("status", "ready") })
                }
                "compile_run" -> compileRun(input)
                "gradle_build" -> gradleBuild(input)
                else -> ToolProtocolResult.notExecuted("UNKNOWN_JVM_OP: $op", true)
            }
        }

        private fun compileRun(input: JsonObject): ToolExecutionResult {
            val sourceCode = input["source_code"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("source_code")
            val mainClass = input["main_class"]?.jsonPrimitive?.content ?: "MainKt"
            val args = input["args"]?.jsonArray?.map { it.jsonPrimitive?.content ?: "" }.filter { it.isNotBlank() } ?: emptyList()

            val result = jvmManager.compileAndRunKotlin(sourceCode, mainClass, args).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("success", result.success)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("exit_code", result.exitCode)
            })
        }

        private fun gradleBuild(input: JsonObject): ToolExecutionResult {
            val projectDirStr = input["project_dir"]?.jsonPrimitive?.content ?: return ToolProtocolResult.missingArgument("project_dir")
            val tasks = input["tasks"]?.jsonArray?.map { it.jsonPrimitive?.content ?: "" }.filter { it.isNotBlank() } ?: listOf("build")
            val gradleArgs = input["gradle_args"]?.jsonArray?.map { it.jsonPrimitive?.content ?: "" }.filter { it.isNotBlank() } ?: emptyList()

            val projectDir = java.io.File(projectDirStr)
            val result = jvmManager.runGradle(projectDir, tasks, gradleArgs).getOrElse { throw it }
            return ToolProtocolResult.success(buildJsonObject {
                put("success", result.success)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("exit_code", result.exitCode)
                put("tasks", tasks.joinToString(","))
            })
        }
    }
}