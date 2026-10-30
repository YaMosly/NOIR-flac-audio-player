package dev.noir.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Opens a user-controlled system setting; never assumes that opening it grants anything. */
object PlaybackPowerSettings {
    fun isExempt(context: Context): Boolean = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    fun requestExemption(context: Context): Boolean {
        if (isExempt(context)) return true
        return open(context, listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(context),
        ))
    }
    fun openAppSettings(context: Context): Boolean = open(context, listOf(appDetails(context), Intent(Settings.ACTION_SETTINGS)))
    private fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    private fun open(context: Context, choices: List<Intent>): Boolean {
        for (intent in choices) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                BackgroundAudioDiagnostics.record(context, "Power setting opened: ${intent.action}")
                return true
            } catch (_: android.content.ActivityNotFoundException) {
                // Some firmware replaces the standard settings activities.
            } catch (_: SecurityException) {
                // Fall back to settings without a direct permission request.
            }
        }
        return false
    }
}
