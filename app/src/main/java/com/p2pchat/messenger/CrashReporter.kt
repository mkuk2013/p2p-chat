package com.p2pchat.messenger

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last-resort crash capture for field diagnosis.
 *
 * Installs a default uncaught-exception handler that synchronously appends
 * the full stack trace (plus breadcrumbs and device/app info) to
 * `filesDir/crash.txt` and, when available, to
 * `getExternalFilesDir(null)/crash.txt`, then delegates to the previously
 * installed handler so the system crash flow is unchanged.
 *
 * Plain framework APIs only — no Compose/Room/mesh code — so it can run as
 * the very first statement of [P2PApp.attachBaseContext] (before the
 * androidx.startup ContentProvider and before [P2PApp.onCreate]) and be
 * read back by [MainActivity] without touching the (possibly broken) app
 * stack.
 */
object CrashReporter {

    private const val CRASH_FILE = "crash.txt"
    private const val CRUMBS_FILE = "breadcrumbs.txt"

    @Volatile
    private var installed = false

    /**
     * Install the handler. Idempotent; call as the first line of
     * Application.attachBaseContext with the base context passed to it.
     * Only Context-level APIs (filesDir / external files dir) are used, so
     * the base context alone is enough — the Application object itself is
     * not fully constructed at that point.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(context, "uncaught on thread '${thread.name}'", throwable)
            } catch (_: Throwable) {
                // Never let reporting mask the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
        breadcrumb(context, "crash-reporter-installed")
    }

    /**
     * Record a caught [throwable] (e.g. from P2PApp's try/catch around
     * container construction) into the same crash file the handler writes.
     */
    fun recordThrowable(context: Context, where: String, throwable: Throwable) {
        try {
            writeReport(context, "caught in $where", throwable)
        } catch (_: Throwable) {
        }
    }

    /** Append a `stage: <stage>` line to filesDir/breadcrumbs.txt. Never throws. */
    fun breadcrumb(context: Context, stage: String) {
        try {
            val line = "stage: $stage @ ${System.currentTimeMillis()}\n"
            FileOutputStream(File(context.filesDir, CRUMBS_FILE), true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Throwable) {
        }
    }

    /** Full crash report (trace + breadcrumbs) if one was recorded, else null. */
    fun readCrash(context: Context): String? {
        for (file in crashFiles(context)) {
            try {
                if (file.exists()) {
                    val text = file.readText(Charsets.UTF_8)
                    if (text.isNotBlank()) return text
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** Delete the recorded crash from all locations so the normal UI can proceed. */
    fun clearCrash(context: Context) {
        for (file in crashFiles(context)) {
            try {
                if (file.exists()) file.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun crashFiles(context: Context): List<File> {
        val files = mutableListOf(File(context.filesDir, CRASH_FILE))
        try {
            context.getExternalFilesDir(null)?.let { files += File(it, CRASH_FILE) }
        } catch (_: Throwable) {
        }
        return files
    }

    private fun readBreadcrumbs(context: Context): String {
        return try {
            val file = File(context.filesDir, CRUMBS_FILE)
            if (file.exists()) file.readText(Charsets.UTF_8).trim().ifEmpty { "(none)" } else "(none)"
        } catch (_: Throwable) {
            "(unreadable)"
        }
    }

    private fun appVersion(context: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.versionName} (code $code)"
        } catch (_: Throwable) {
            "(unknown)"
        }
    }

    private fun writeReport(context: Context, where: String, throwable: Throwable) {
        val trace = StringWriter().also { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
        }.toString()
        val report = buildString {
            append("==== P2P Chat crash report ====\n")
            append("when: ").append(System.currentTimeMillis()).append('\n')
            append("where: ").append(where).append('\n')
            append("app: ").append(appVersion(context)).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("---- breadcrumbs ----\n")
            append(readBreadcrumbs(context)).append('\n')
            append("---- stack trace ----\n")
            append(trace).append('\n')
        }
        for (file in crashFiles(context)) {
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                    out.flush()
                    try {
                        out.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
