package dev.noir.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Settings and observed route are independent: enabling USB does not claim it is playing. */
object DirectUsbSettings {
    val enabled = MutableStateFlow(false)
    val db = MutableStateFlow(-35)
    val status = MutableStateFlow("Android · прямой USB выключен")
    val report = MutableStateFlow("")
    val owned = MutableStateFlow(false)
    val failure = MutableStateFlow<String?>(null)
    val decodedHash = MutableStateFlow("")
    private lateinit var prefs: android.content.SharedPreferences
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("direct_usb", Context.MODE_PRIVATE)
        enabled.value = prefs.getBoolean("enabled", false)
        db.value = prefs.getInt("db", -35).coerceIn(-50, 0)
    }
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply(); enabled.value = value }
    fun setDb(value: Int) { db.value = value.coerceIn(-50, 0); prefs.edit().putInt("db", db.value).apply() }
}

/** One hardware connection owner, including diagnostics, across both loopers. */
object UsbHardwareGate {
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any): Boolean { if (owner != null) return false; owner = token; return true }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
}
