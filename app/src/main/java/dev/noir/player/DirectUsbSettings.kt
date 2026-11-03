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
    val device = MutableStateFlow("USB-ЦАП")
    val failure = MutableStateFlow<String?>(null)
    val decodedHash = MutableStateFlow("")
    private lateinit var prefs: android.content.SharedPreferences
    private var profile: String? = null
    var activeDeviceName: String? = null
        private set
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("direct_usb", Context.MODE_PRIVATE)
        enabled.value = prefs.getBoolean("enabled", false)
        // Legacy global levels are deliberately not copied onto an unknown DAC.
        profile = null; activeDeviceName = null; db.value = -35
    }
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply(); enabled.value = value }
    @Synchronized fun bindDevice(vendor: Int, product: Int, serial: String?, name: String) {
        val key = DacVolumeProfiles.key(vendor, product, serial)
        activeDeviceName = name
        if (profile == key) return
        profile = key
        db.value = prefs.getInt("volume_$key", -35).coerceIn(-50, 0)
    }
    @Synchronized fun setDb(value: Int) {
        db.value = value.coerceIn(-50, 0)
        profile?.let { prefs.edit().putInt("volume_$it", db.value).apply() }
    }
}

object DacVolumeProfiles {
    fun key(vendor: Int, product: Int, serial: String?): String {
        val unit = serial?.takeIf(String::isNotBlank)?.let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b) }
        } ?: "model"
        return "$vendor:$product:$unit"
    }
}

/** One hardware connection owner, including diagnostics, across both loopers. */
object UsbHardwareGate {
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any): Boolean { if (owner != null) return false; owner = token; return true }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
}
