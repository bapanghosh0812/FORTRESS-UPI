package com.fortress.upi.core.security.integrity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import androidx.annotation.WorkerThread
import com.fortress.upi.core.crypto.Fce
import com.fortress.upi.core.crypto.constantTimeEquals
import com.fortress.upi.core.crypto.hexToBytes
import com.fortress.upi.core.crypto.sha256
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

enum class Severity { INFO, ELEVATED, CRITICAL }

class IntegritySignal(val id: String, val severity: Severity)

class IntegrityReport(val signals: List<IntegritySignal>) {
    val maxSeverity: Severity = signals.maxOfOrNull { it.severity } ?: Severity.INFO

    /** Hooking, an attached debugger or a re-signed APK block money movement locally. */
    val blocksPayments: Boolean get() = maxSeverity == Severity.CRITICAL

    fun encode(): ByteArray = Fce.Writer()
        .list(1, signals.map { "${it.id}:${it.severity.name}".toByteArray(Charsets.UTF_8) })
        .u64(2, maxSeverity.ordinal.toLong())
        .build()
}

/**
 * RASP signal collection (root, hooks, debugger, tamper, emulator).
 *
 * These checks run in app code and *can* be bypassed by a determined attacker
 * with full device control; they are evidence, not authority. The authoritative
 * decision is server-side: hardware key attestation (verified boot state, app
 * signature digest) + Play Integrity verdicts. For production, pair this with a
 * commercial RASP/obfuscator (DexGuard, Appdome, Promon SHIELD) that moves checks
 * into native code with integrity self-verification.
 */
class RuntimeIntegrityScanner(
    private val context: Context,
    /** Hex SHA-256 of the Play App Signing certificate; null/blank disables the check. */
    private val expectedSignerSha256: String?,
    private val releaseBuild: Boolean,
) {
    @WorkerThread
    fun scan(): IntegrityReport = IntegrityReport(
        buildList {
            addAll(rootSignals())
            addAll(hookSignals())
            addAll(debugSignals())
            addAll(tamperSignals())
            addAll(emulatorSignals())
        },
    )

    private fun rootSignals(): List<IntegritySignal> = buildList {
        if (SU_PATHS.any { File(it).exists() }) add(IntegritySignal("root.su_binary", Severity.ELEVATED))
        if (Build.TAGS?.contains("test-keys") == true) add(IntegritySignal("root.test_keys", Severity.ELEVATED))
        if (ROOT_PACKAGES.any(::isInstalled)) add(IntegritySignal("root.manager_app", Severity.ELEVATED))
        if (readLines("/proc/mounts").any { it.contains(" /system ") && it.contains(" rw,") }) {
            add(IntegritySignal("root.system_rw", Severity.ELEVATED))
        }
    }

    private fun hookSignals(): List<IntegritySignal> = buildList {
        val maps = readLines("/proc/self/maps")
        if (maps.any { line -> HOOK_LIBS.any { line.contains(it, ignoreCase = true) } }) {
            add(IntegritySignal("hook.injected_library", Severity.CRITICAL))
        }
        val threadNames = File("/proc/self/task").listFiles().orEmpty()
            .mapNotNull { runCatching { File(it, "comm").readText().trim() }.getOrNull() }
        if (threadNames.any { name -> HOOK_THREADS.any { name.contains(it) } }) {
            add(IntegritySignal("hook.agent_thread", Severity.CRITICAL))
        }
        if (FRIDA_PORTS.any(::localPortOpen)) add(IntegritySignal("hook.frida_server", Severity.CRITICAL))
        if (classPresent("de.robv.android.xposed.XposedBridge") ||
            Throwable().stackTrace.any { it.className.contains("xposed", ignoreCase = true) || it.className.contains("lsposed", ignoreCase = true) }
        ) {
            add(IntegritySignal("hook.xposed", Severity.CRITICAL))
        }
    }

    private fun debugSignals(): List<IntegritySignal> = buildList {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            add(IntegritySignal("debug.debugger_attached", if (releaseBuild) Severity.CRITICAL else Severity.INFO))
        }
        val tracer = readLines("/proc/self/status").firstOrNull { it.startsWith("TracerPid:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (tracer != 0) add(IntegritySignal("debug.ptrace", if (releaseBuild) Severity.CRITICAL else Severity.INFO))
        if (releaseBuild && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            add(IntegritySignal("debug.debuggable_release", Severity.CRITICAL))
        }
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) {
            add(IntegritySignal("debug.adb_enabled", Severity.INFO))
        }
    }

    private fun tamperSignals(): List<IntegritySignal> = buildList {
        val expected = expectedSignerSha256?.takeIf { it.isNotBlank() }?.lowercase()?.hexToBytes()
        if (expected != null) {
            val signers = packageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
            if (signers.none { sha256(it.toByteArray()).constantTimeEquals(expected) }) {
                add(IntegritySignal("tamper.signature_mismatch", Severity.CRITICAL))
            }
        }
        if (releaseBuild) {
            val installer = runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
            if (installer !in TRUSTED_INSTALLERS) add(IntegritySignal("tamper.untrusted_installer", Severity.ELEVATED))
        }
    }

    private fun emulatorSignals(): List<IntegritySignal> = buildList {
        val fp = Build.FINGERPRINT.orEmpty()
        val emulator = fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") ||
            Build.HARDWARE in setOf("goldfish", "ranchu") || Build.PRODUCT.orEmpty().contains("sdk")
        if (emulator) add(IntegritySignal("env.emulator", if (releaseBuild) Severity.ELEVATED else Severity.INFO))
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun packageInfo(pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, flags)
        }

    private fun classPresent(name: String): Boolean = try {
        Class.forName(name, false, ClassLoader.getSystemClassLoader())
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    private fun localPortOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS) }
        true
    } catch (e: IOException) {
        false
    }

    private fun readLines(path: String): List<String> = try {
        File(path).useLines { it.take(MAX_LINES).toList() }
    } catch (e: IOException) {
        emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    private companion object {
        const val MAX_LINES = 20_000
        const val PORT_PROBE_TIMEOUT_MS = 40
        val FRIDA_PORTS = listOf(27042, 27043)
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/system/bin/.ext/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/su/bin/su", "/debug_ramdisk/su",
            "/sbin/.magisk", "/cache/.disable_magisk",
        )
        val ROOT_PACKAGES = listOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "me.weishu.kernelsu",
            "eu.chainfire.supersu", "com.koushikdutta.superuser", "org.lsposed.manager",
            "de.robv.android.xposed.installer",
        )
        val HOOK_LIBS = listOf("frida", "gum-js", "gadget", "xposed", "lspd", "lsposed", "riru", "substrate", "zygisk")
        val HOOK_THREADS = listOf("gum-js-loop", "gmain", "gdbus", "pool-frida", "linjector")
        val TRUSTED_INSTALLERS = setOf("com.android.vending")
    }
}
