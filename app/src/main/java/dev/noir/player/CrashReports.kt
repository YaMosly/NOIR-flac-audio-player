package dev.noir.player

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Private, local crash evidence; the Android default crash handler still terminates the process. */
object CrashReports {
    private var directory: File? = null
    private var installed = false
    var previousExit: String = ""
        private set

    fun install(context: Context) {
        if (installed) return
        installed = true
        directory = context.filesDir
        previousExit = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()?.let { "Предыдущее завершение Android: reason=${it.reason}, status=${it.status}, UTC=${java.time.Instant.ofEpochMilli(it.timestamp)}; ${it.description.orEmpty().take(2048)}" }.orEmpty()
        }.getOrDefault("")
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            save("last-java-crash.txt", "Поток: ${thread.name}", failure)
            if (original != null) original.uncaughtException(thread, failure)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
    fun usbLinkage(failure: LinkageError) = save("last-usb-linkage.txt", "Загрузка USB-движка", failure)
    private fun save(name: String, label: String, failure: Throwable) {
        runCatching {
            val folder = directory ?: return
            val text = "NOIR ${BuildConfig.VERSION_NAME} · ${java.time.Instant.now()}\n$label\n${failure.stackTraceToString()}".take(32_768)
            FileOutputStream(File(folder, name)).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
    }
    fun report(): String = buildString {
        if (previousExit.isNotBlank()) appendLine(previousExit)
        for (name in listOf("last-java-crash.txt", "last-usb-linkage.txt")) {
            val file = directory?.let { File(it, name) } ?: continue
            val text = runCatching { if (file.isFile) file.inputStream().use { it.readNBytes(32_768).toString(Charsets.UTF_8) } else "" }.getOrDefault("")
            if (text.isNotEmpty()) { appendLine(); appendLine(text) }
        }
    }
}
