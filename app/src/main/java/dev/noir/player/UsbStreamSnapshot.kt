package dev.noir.player

/** All three queries are IN/GET. No state-changing request is expressible here. */
fun interface UsbStatusRead {
    fun get(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray?
}

data class UsbStreamSnapshot(val alternate: Int?, val endpointStatus: Int?, val clockValid: Int?) {
    fun report(stage: String): String = "$stage: AS1 GET_INTERFACE=${alternate ?: "нет ответа"}; EP04 GET_STATUS=${endpointStatus?.let { "0x%04X".format(it) } ?: "нет ответа"}; Clock6 valid=${when (clockValid) { 0 -> "false"; 1 -> "true"; null -> "нет ответа"; else -> "неизвестно ($clockValid)" }}"
    companion object {
        fun capture(reader: UsbStatusRead): UsbStreamSnapshot {
            fun query(type: Int, request: Int, value: Int, index: Int, length: Int): Int? =
                runCatching { reader.get(type, request, value, index, length)?.takeIf { it.size == length }?.let { UsbControlValues.unsigned(it).toInt() } }.getOrNull()
            return UsbStreamSnapshot(
                alternate = query(0x81, 0x0a, 0, 1, 1), // standard GET_INTERFACE, AudioStreaming 1
                endpointStatus = query(0x82, 0, 0, 4, 2), // standard GET_STATUS, endpoint 04
                clockValid = query(0xa1, 1, 0x0200, 0x0600, 1), // UAC2 Clock Validity CUR
            )
        }
    }
}
