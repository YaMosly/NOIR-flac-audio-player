package dev.noir.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Accessed on the main looper. Does not resume playback or alter volume on release. */
object UsbPlaybackInterlock {
    private val mutableActive = MutableStateFlow(false)
    val active = mutableActive.asStateFlow()
    private var owner: Any? = null
    var stopPlayback: (() -> Unit)? = null

    fun acquire(token: Any): Boolean {
        if (owner != null) return false
        owner = token
        mutableActive.value = true
        try { stopPlayback?.invoke() } catch (failure: Exception) { release(token); throw failure }
        return true
    }

    fun release(token: Any) {
        if (owner !== token) return
        owner = null
        mutableActive.value = false
    }
}
