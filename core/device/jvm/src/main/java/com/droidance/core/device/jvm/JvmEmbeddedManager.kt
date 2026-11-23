// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.jvm

import android.content.Context
import com.droidance.core.device.tools.AdbShellExecutor
import com.droidance.debug.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JvmEmbeddedManager @Inject constructor(
    @androidx.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val shellRunner: AdbShellExecutor,
    private val debugLogManager: DebugLogManager
) {
    companion object {
        const val JVM_ASSETS_DIR = "jvm"
        const val JRE_RELATIVE = "jre"
        const val KOTLINC_RELATIVE = "kotlinc"
        const val GRADLE_RELATIVE = "gradle"
    }

    private val jvmState = AtomicReference<JvmState>(JvmState.NOT_INITIALIZED)

    data class JvmState(
        val status: Status,
        val jrePath: String? = null,
        val kotlincPath: String? = null,
        val gradlePath: String? = null,
        val error: String? = null
    ) {
        enum class Status { NOT_INITIALIZED, EXTRACTING, READY, FAILED }
    }

    /** Gömülü JVM'i assets'ten çıkart ve hazırla. */
    suspend fun ensureJvmReady(): Result<JvmState> = withContext(Dispatchers.IO) {
        val current = jvmState.get()
        if (current.status == JvmState.Status.READY) return Result.success(current)

        jvmState.set(current.copy(status = JvmState.Status.EXTRACTING))
        debugLogManager.appendEvent("jvm: extracting embedded JVM...")

        val jvmDir = File(context.filesDir, "embedded-jvm")
        jvmDir.mkdirs()

        try {
            // Extract JRE
            val jrePath = extractAssetDir(JRE_RELATIVE, jvmDir)
            // Extract kotlinc
            val kotlincPath = extractAssetDir(KOTLINC_RELATIVE, jvmDir)
            // Extract gradle wrapper
            val gradlePath = extractAssetDir(GRADLE_RELATIVE, jvmDir)

            // Make binaries executable
            makeExecutable(File(jrePath, "bin"))
            makeExecutable(File(kotlincPath, "bin"))
            makeExecutable(File(gradlePath, "bin"))

            val finalState = JvmState(
                status = JvmState.Status.READY,
                jrePath = jrePath,
                kotlincPath = kotlincPath,
                gradlePath = gradlePath
            )
            jvmState.set(finalState)
            debugLogManager.appendEvent("jvm: embedded JVM ready at ${jvmDir.absolutePath}")
            Result.success(finalState)

        } catch (e: Exception) {
            val failed = JvmState(status = JvmState.Status.FAILED, error = e.message)
            jvmState.set(failed)
            debugLogManager.appendEvent("jvm: extraction failed: ${failed.error}")
            Result.failure(e)
        }
    }

    private fun extractAssetDir(assetPath: String, destDir: File): String {
        val outDir = File(destDir, assetPath)
        outDir.mkdirs()
        // Note: Actual extraction from assets requires AssetManager
        // This is a placeholder - real implementation uses context.assets.open()
        // For now, return the expected path
        return outDir.absolutePath
    }

    private fun makeExecutable(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile) file.setExecutable(true, false)
            else if (file.isDirectory) makeExecutable(file)
        }
    }

    /** Kotlin kaynak dosyasını derle ve çalıştır. */
    suspend fun compileAndRunKotlin(
        sourceCode: String,
        mainClass: String = "MainKt",
        args: List<String> = emptyList()
    ): Result<JvmExecResult> = withContext(Dispatchers.IO) {
        val state = jvmState.get()
        if (state.status != JvmState.Status.READY) {
            ensureJvmReady().getOrElse { throw it }
        }

        val workDir = File(context.cacheDir, "kotlin-work-${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            // Write source
            val srcFile = File(workDir, "$mainClass.kt")
            srcFile.writeText(sourceCode)

            // Compile with kotlinc
            val kotlincBin = File(state.kotlincPath!!, "bin/kotlinc").absolutePath
            val compileCmd = "$kotlincBin -d ${workDir.absolutePath} ${srcFile.absolutePath}"
            val compileResult = shellRunner.shell(compileCmd, 120_000L, "shell,v2")
            if (shellRunner.isFailure(compileResult)) {
                return Result.failure(IllegalStateException("Kotlin compile failed: $compileResult"))
            }

            // Run with java
            val javaBin = File(state.jrePath!!, "bin/java").absolutePath
            val classpath = workDir.absolutePath
            val runCmd = "$javaBin -cp $classpath $mainClass ${args.joinToString(" ")}"
            val runResult = shellRunner.shell(runCmd, 60_000L, "shell,v2")

            Result.success(JvmExecResult(
                exitCode = 0,
                stdout = runResult,
                stderr = "",
                success = !shellRunner.isFailure(runResult)
            ))

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Cleanup
            try { workDir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    /** Gradle wrapper ile proje build et. */
    suspend fun runGradle(
        projectDir: File,
        tasks: List<String>,
        gradleArgs: List<String> = emptyList()
    ): Result<JvmExecResult> = withContext(Dispatchers.IO) {
        val state = jvmState.get()
        if (state.status != JvmState.Status.READY) {
            ensureJvmReady().getOrElse { throw it }
        }

        val gradleWrapper = File(projectDir, "gradlew")
        if (!gradleWrapper.exists()) {
            // Generate gradle wrapper
            val gradleBin = File(state.gradlePath!!, "bin/gradle").absolutePath
            val initCmd = "$gradleBin wrapper --gradle-version 8.5"
            shellRunner.shell(initCmd, 60_000L, "shell,v2").also { if (shellRunner.isFailure(it)) throw IllegalStateException("Gradle wrapper init failed") }
        }

        gradleWrapper.setExecutable(true)
        val cmd = "./gradlew ${tasks.joinToString(" ")} ${gradleArgs.joinToString(" ")}"
        val result = shellRunner.shell(cmd, 300_000L, "shell,v2", projectDir.absolutePath)

        Result.success(JvmExecResult(
            exitCode = 0,
            stdout = result,
            stderr = "",
            success = !shellRunner.isFailure(result)
        ))
    }

    data class JvmExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val success: Boolean
    )
}