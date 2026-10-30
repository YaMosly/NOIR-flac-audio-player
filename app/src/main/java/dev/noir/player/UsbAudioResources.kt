package dev.noir.player

import android.content.Context
import android.os.PowerManager
import android.os.Process

/** Bound to this worker and this short experiment; both resources are released on every exit. */
object UsbAudioResources {
    fun <T> run(context: Context, block: (String) -> T): T {
        val tid = Process.myTid()
        val previousPriority = Process.getThreadPriority(tid)
        val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NOIR:UsbPreview")
        try {
            val priorityError = runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }.exceptionOrNull()
            wake.acquire(30_000)
            val description = "Worker nice=${Process.getThreadPriority(tid)}; CPU wake lock=${wake.isHeld}" + (priorityError?.let { "; audio priority not granted: ${it.javaClass.simpleName}" } ?: "")
            return block(description)
        } finally {
            try { if (wake.isHeld) wake.release() } finally { Process.setThreadPriority(previousPriority) }
        }
    }
}
