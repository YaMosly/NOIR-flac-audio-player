package dev.noir.player

import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean

/** The native handle owns a duplicate fd and all URB storage until destroy, after Java closes USB. */
@Keep
object NativeUsb {
    init { System.loadLibrary("noir_usb") }
    external fun create(fd: Int): Long
    external fun setLiveStopped(handle: Long, stopped: Boolean)
    external fun configureLive(handle: Long, rate: Int, frameBytes: Int)
    external fun speed(handle: Long): Int
    external fun stream(handle: Long, endpoint: Int, interval: Int, maxPacket: Int, pcm: ByteArray, stop: AtomicBoolean): String
    external fun destroy(handle: Long)
    external fun feedLive(handle: Long, buffer: java.nio.ByteBuffer, offset: Int, size: Int): Int
    external fun finishLive(handle: Long)
    external fun resetLive(handle: Long)
    external fun livePosition(handle: Long): Long
    external fun liveBuffered(handle: Long): Long
    external fun streamLive(handle: Long, endpoint: Int, interval: Int, maxPacket: Int): String
    external fun packetPlan(rate: Int, intervals: Int, count: Int): IntArray
}
