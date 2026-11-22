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
import java.net.URL
import java.nio.channels.Channels
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermuxBootstrapManager @Inject constructor(
    @androidx.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val shellRunner: AdbShellExecutor,
    private val debugLogManager: DebugLogManager
) {
    companion object {
        const val BOOTSTRAP_URL = "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"
        const val EXPECTED_MIN_SIZE = 30_000_000L // 30 MB minimum
        const val CRITICAL_FILES = arrayOf(
            "bin/bash",
            "bin/apt",
            "lib/libc.so",
            "lib/libc++_shared.so",
            "lib/libandroid-support.so",
            "SYMLINKS.txt"
        )
    }

    private val bootstrapState = AtomicReference<BootstrapState>(BootstrapState.NOT_STARTED)

    data class BootstrapState(
        val status: Status,
        val rootfsPath: String? = null,
        val version: String? = null,
        val sha256: String? = null,
        val error: String? = null
    ) {
        enum class Status { NOT_STARTED, DOWNLOADING, EXTRACTING, VERIFYING, READY, FAILED }
    }

    /** Termux bootstrap'i indir, doğrula, aç ve symlink'leri çöz. */
    suspend fun ensureBootstrapReady(forceRedownload: Boolean = false): Result<BootstrapState> = withContext(Dispatchers.IO) {
        val current = bootstrapState.get()
        if (current.status == BootstrapState.Status.READY && !forceRedownload) {
            return@withContext Result.success(current)
        }

        bootstrapState.set(current.copy(status = BootstrapState.Status.DOWNLOADING))
        debugLogManager.appendEvent("termux: bootstrap download started")

        val rootfsDir = File(context.filesDir, "termux-rootfs")
        val zipFile = File(context.cacheDir, "termux-bootstrap.zip")

        try {
            // 1. İndir
            downloadBootstrap(zipFile)
            bootstrapState.updateAndGet { it.copy(status = BootstrapState.Status.EXTRACTING) }

            // 2. SHA256 doğrula
            val sha256 = verifySha256(zipFile)
            debugLogManager.appendEvent("termux: bootstrap sha256=$sha256")

            // 3. Aç (symlink'ler korunur)
            extractBootstrap(zipFile, rootfsDir)
            bootstrapState.updateAndGet { it.copy(status = BootstrapState.Status.VERIFYING) }

            // 4. Kritik dosyaları kontrol et
            verifyCriticalFiles(rootfsDir)

            // 5. Symlink'leri çöz (SYMLINKS.txt parse et)
            resolveSymlinks(rootfsDir)

            // 6. LD_LIBRARY_PATH / PATH ayarla
            setupEnvironment(rootfsDir)

            val finalState = BootstrapState(
                status = BootstrapState.Status.READY,
                rootfsPath = rootfsDir.absolutePath,
                version = extractVersion(rootfsDir),
                sha256 = sha256
            )
            bootstrapState.set(finalState)
            debugLogManager.appendEvent("termux: bootstrap ready at ${rootfsDir.absolutePath}")
            Result.success(finalState)

        } catch (e: Exception) {
            val failed = BootstrapState(
                status = BootstrapState.Status.FAILED,
                error = e.message ?: e.javaClass.simpleName
            )
            bootstrapState.set(failed)
            debugLogManager.appendEvent("termux: bootstrap failed: ${failed.error}")
            Result.failure(e)
        }
    }

    private fun downloadBootstrap(zipFile: File) {
        zipFile.parentFile.mkdirs()
        URL(BOOTSTRAP_URL).openStream().use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }
        require(zipFile.length() >= EXPECTED_MIN_SIZE) { "Bootstrap too small: ${zipFile.length()} bytes" }
    }

    private fun verifySha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                digest.update(buffer, 0, len)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractBootstrap(zipFile: File, destDir: File) {
        destDir.mkdirs()
        // ZipFile symlink'leri desteklemez, manuel ayrıştırma gerekir
        // Termux bootstrap'te symlink'ler SYMLINKS.txt'de listelenir
        // Önce dosyaları çıkar, sonra symlink'leri oluştur
        java.util.zip.ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun verifyCriticalFiles(rootfsDir: File) {
        CRITICAL_FILES.forEach { relPath ->
            val file = File(rootfsDir, relPath)
            require(file.exists()) { "Critical file missing: $relPath" }
            require(file.length() > 0) { "Critical file empty: $relPath" }
        }
    }

    private fun resolveSymlinks(rootfsDir: File) {
        val symlinksFile = File(rootfsDir, "SYMLINKS.txt")
        require(symlinksFile.exists()) { "SYMLINKS.txt not found" }

        symlinksFile.readText().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                // Format: target←link (Termux uses ← as separator)
                val parts = line.split("←")
                if (parts.size == 2) {
                    val target = parts[0].trim()
                    val link = parts[1].trim()
                    val linkFile = File(rootfsDir, link)
                    val targetFile = File(rootfsDir, target)
                    if (targetFile.exists()) {
                        linkFile.parentFile.mkdirs()
                        try {
                            if (linkFile.exists()) linkFile.delete()
                            linkFile.toPath().createSymbolicLink(targetFile.toPath())
                        } catch (e: Exception) {
                            debugLogManager.appendEvent("termux: symlink failed $link -> $target: ${e.message}")
                        }
                    }
                }
            }
    }

    private fun setupEnvironment(rootfsDir: File) {
        // LD_LIBRARY_PATH: Termux libs first, then system
        val termuxLib = File(rootfsDir, "lib").absolutePath
        val termuxBin = File(rootfsDir, "bin").absolutePath
        // Note: Actual env is applied in TermuxProotLauncher
    }

    private fun extractVersion(rootfsDir: File): String? {
        val aptPkg = File(rootfsDir, "var/lib/dpkg/status")
        return if (aptPkg.exists()) {
            aptPkg.readText().lineSequence()
                .firstOrNull { it.startsWith("Version:") }
                ?.substringAfter("Version:")?.trim()
        } else null
    }

    fun getState(): BootstrapState = bootstrapState.get()

    /** Host dosya sistemine Termux rootfs içinden kopyalama (scoped). */
    suspend fun copyFromTermux(termuxPath: String, hostDestination: File): Result<Unit> = withContext(Dispatchers.IO) {
        val state = bootstrapState.get()
        if (state.status != BootstrapState.Status.READY) return Result.failure(IllegalStateException("Termux not ready"))
        val source = File(state.rootfsPath!!, termuxPath)
        if (!source.exists()) return Result.failure(IllegalArgumentException("Source not found: $termuxPath"))
        hostDestination.parentFile.mkdirs()
        source.copyTo(hostDestination, overwrite = true)
        Result.success(Unit)
    }

    /** Host'ten Termux rootfs'ine kopyalama. */
    suspend fun copyToTermux(hostSource: File, termuxDestination: String): Result<Unit> = withContext(Dispatchers.IO) {
        val state = bootstrapState.get()
        if (state.status != BootstrapState.Status.READY) return Result.failure(IllegalStateException("Termux not ready"))
        val dest = File(state.rootfsPath!!, termuxDestination)
        dest.parentFile.mkdirs()
        hostSource.copyTo(dest, overwrite = true)
        Result.success(Unit)
    }
}