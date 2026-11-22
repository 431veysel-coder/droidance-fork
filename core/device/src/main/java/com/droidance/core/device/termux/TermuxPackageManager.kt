// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.termux

import com.droidance.core.device.tools.AdbShellExecutor
import com.droidance.debug.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermuxPackageManager @Inject constructor(
    private val prootLauncher: TermuxProotLauncher,
    private val debugLogManager: DebugLogManager
) {
    private val packageCache = mutableMapOf<String, PackageInfo>()
    private val cacheValid = mutableMapOf<String, Boolean>()

    data class PackageInfo(
        val name: String,
        val version: String,
        val description: String,
        val installed: Boolean,
        val size: Long
    )

    /** Paket listesini güncelle (apt update). */
    suspend fun updatePackageList(): Result<Unit> = withContext(Dispatchers.IO) {
        debugLogManager.appendEvent("termux: apt update started")
        val result = prootLauncher.executeCommand("apt update -y", timeoutMs = 180_000L)
        if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("apt update failed"))
        cacheValid.clear()
        debugLogManager.appendEvent("termux: apt update completed")
        Result.success(Unit)
    }

    /** Paket ara. */
    suspend fun searchPackages(query: String): Result<List<PackageInfo>> = withContext(Dispatchers.IO) {
        if (!cacheValid.getOrDefault("search:$query", false)) {
            val result = prootLauncher.executeCommand("apt search $query 2>/dev/null", timeoutMs = 60_000L)
            if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("apt search failed"))
            val packages = parseSearchOutput(result.getOrThrow().stdout)
            packageCache.clear()
            packages.forEach { packageCache[it.name] = it }
            cacheValid["search:$query"] = true
        }
        Result.success(packageCache.values.filter { it.name.contains(query, ignoreCase = true) }.toList())
    }

    /** Paket kur. */
    suspend fun installPackage(
        packageName: String,
        assumeYes: Boolean = true,
        noInstallRecommends: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        debugLogManager.appendEvent("termux: installing $packageName")
        val flags = buildFlags(assumeYes, noInstallRecommends)
        val result = prootLauncher.executeCommand("apt install $flags $packageName", timeoutMs = 300_000L)
        if (result.isFailure) {
            debugLogManager.appendEvent("termux: install failed for $packageName: ${result.exceptionOrNull()?.message}")
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException("apt install failed"))
        }
        cacheValid.remove("installed")
        cacheValid.remove("search:")
        debugLogManager.appendEvent("termux: installed $packageName")
        Result.success(Unit)
    }

    /** Paket kaldır. */
    suspend fun removePackage(packageName: String, purge: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val flags = if (purge) "--purge -y" else "-y"
        val result = prootLauncher.executeCommand("apt remove $flags $packageName", timeoutMs = 120_000L)
        if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("apt remove failed"))
        cacheValid.remove("installed")
        Result.success(Unit)
    }

    /** Kurulu paketleri listele. */
    suspend fun listInstalledPackages(): Result<List<PackageInfo>> = withContext(Dispatchers.IO) {
        if (!cacheValid.getOrDefault("installed", false)) {
            val result = prootLauncher.executeCommand("dpkg -l | grep '^ii'", timeoutMs = 30_000L)
            if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("dpkg -l failed"))
            val packages = parseDpkgOutput(result.getOrThrow().stdout)
            packageCache.clear()
            packages.forEach { packageCache[it.name] = it }
            cacheValid["installed"] = true
        }
        Result.success(packageCache.values.filter { it.installed }.toList())
    }

    /** Paket kurulu mu? */
    suspend fun isPackageInstalled(packageName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val result = prootLauncher.executeCommand("dpkg -s $packageName 2>/dev/null | grep '^Status: install ok installed'")
        Result.success(result.isSuccess && result.getOrThrow().stdout.isNotBlank())
    }

    /** JDK/Gradle kurulu mu, değilse kur. */
    suspend fun ensureJdkAndGradle(): Result<Unit> = withContext(Dispatchers.IO) {
        val jdkInstalled = isPackageInstalled("openjdk-21").getOrElse(false)
        val gradleInstalled = isPackageInstalled("gradle").getOrElse(false)

        if (!jdkInstalled) {
            debugLogManager.appendEvent("termux: installing openjdk-21")
            installPackage("openjdk-21").getOrThrow()
        }
        if (!gradleInstalled) {
            debugLogManager.appendEvent("termux: installing gradle")
            installPackage("gradle").getOrThrow()
        }
        Result.success(Unit)
    }

    private fun buildFlags(assumeYes: Boolean, noInstallRecommends: Boolean): String {
        return buildList {
            if (assumeYes) add("-y")
            if (noInstallRecommends) add("--no-install-recommends")
            add("-o Dpkg::Options::=--force-confdef")
            add("-o Dpkg::Options::=--force-confold")
        }.joinToString(" ")
    }

    private fun parseSearchOutput(output: String): List<PackageInfo> {
        return output.lineSequence()
            .filter { it.contains("/") && !it.startsWith("Sorting") && !it.startsWith("Full Text") }
            .mapNotNull { line ->
                val parts = line.split("/")
                if (parts.size >= 2) {
                    val nameVer = parts[1].split(" ").firstOrNull() ?: return@mapNotNull null
                    val desc = line.split(" - ").lastOrNull() ?: ""
                    PackageInfo(nameVer, "", desc, false, 0)
                } else null
            }
            .toList()
    }

    private fun parseDpkgOutput(output: String): List<PackageInfo> {
        return output.lineSequence()
            .filter { it.startsWith("ii") }
            .mapNotNull { line ->
                val parts = line.split("\\s+".toRegex()).drop(1) // skip "ii"
                if (parts.size >= 3) {
                    val name = parts[0]
                    val version = parts[1]
                    PackageInfo(name, version, "", true, 0)
                } else null
            }
            .toList()
    }
}