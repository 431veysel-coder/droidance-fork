// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.DeviceToolNames
import com.droidance.core.agent.tools.Tool
import com.droidance.core.agent.tools.ToolDefinition
import com.droidance.core.agent.tools.ToolParameter
import com.droidance.core.agent.tools.ToolProtocolResult
import com.droidance.core.agent.tools.ToolTier
import com.droidance.core.device.browser.BrowserToolRegistrar
import com.droidance.core.device.jvm.JvmToolRegistrar
import com.droidance.core.device.termux.TermuxToolRegistrar
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedDeviceToolRegistrar @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val browserToolRegistrar: BrowserToolRegistrar,
    private val termuxToolRegistrar: TermuxToolRegistrar,
    private val jvmToolRegistrar: JvmToolRegistrar,
    // Existing registrars
    private val androidReadTool: AndroidReadTool,
    private val androidActTool: AndroidActTool,
    private val shellTool: ShellTool,
    private val screenActTool: ScreenActTool,
    private val programTool: ProgramTool
) {
    fun registerAll() {
        // Existing tools
        register(androidReadTool)
        register(androidActTool)
        register(shellTool)
        register(screenActTool)
        register(programTool)

        // NEW: Browser, Termux, JVM
        browserToolRegistrar.registerAll()
        termuxToolRegistrar.registerAll()
        jvmToolRegistrar.registerAll()

        // Register this registrar's register function for new tools
        // (Hilt will inject the register function via ToolExecutor)
    }

    // Tool registration function for Hilt
    fun register(tool: Tool) {
        ToolExecutor.registerStatic(tool)
    }

    // Existing tool classes (placeholders - actual implementations in original code)
    @Suppress("UNUSED_PARAMETER")
    private class AndroidReadTool : Tool() {
        override val name = DeviceToolNames.ANDROID_READ
        override val tier = ToolTier.DEVICE
        override val definition = ToolDefinition(name, "Read Android content", emptyMap())
        override suspend fun execute(arguments: String): ToolExecutionResult = ToolProtocolResult.notExecuted("NOT_IMPLEMENTED", false)
    }

    @Suppress("UNUSED_PARAMETER")
    private class AndroidActTool : Tool() {
        override val name = DeviceToolNames.ANDROID_ACT
        override val tier = ToolTier.DEVICE
        override val definition = ToolDefinition(name, "Android actions", emptyMap())
        override suspend fun execute(arguments: String): ToolExecutionResult = ToolProtocolResult.notExecuted("NOT_IMPLEMENTED", false)
    }

    @Suppress("UNUSED_PARAMETER")
    private class ShellTool : Tool() {
        override val name = DeviceToolNames.SHELL
        override val tier = ToolTier.DEVICE
        override val definition = ToolDefinition(name, "Shell commands", emptyMap())
        override suspend fun execute(arguments: String): ToolExecutionResult = ToolProtocolResult.notExecuted("NOT_IMPLEMENTED", false)
    }

    @Suppress("UNUSED_PARAMETER")
    private class ScreenActTool : Tool() {
        override val name = DeviceToolNames.SCREEN_ACT
        override val tier = ToolTier.DEVICE
        override val definition = ToolDefinition(name, "Screen actions", emptyMap())
        override suspend fun execute(arguments: String): ToolExecutionResult = ToolProtocolResult.notExecuted("NOT_IMPLEMENTED", false)
    }

    @Suppress("UNUSED_PARAMETER")
    private class ProgramTool : Tool() {
        override val name = DeviceToolNames.WORKSPACE
        override val tier = ToolTier.DEVICE
        override val definition = ToolDefinition(name, "Workspace tool", emptyMap())
        override suspend fun execute(arguments: String): ToolExecutionResult = ToolProtocolResult.notExecuted("NOT_IMPLEMENTED", false)
    }
}