package dev.noir.player

/** No USB I/O here: every entity must belong to the selected playback path. */
data class UsbPlaybackEndpoint(val iface: Int, val alternate: Int, val address: Int, val attributes: Int, val interval: Int, val maxPacket: Int)
data class UsbPlaybackPlan(val format: UsbStreamFormat, val endpoint: UsbPlaybackEndpoint, val clock: UsbClock, val volumeUnit: Int, val volumeChannels: List<Int>, val feedback: UsbPlaybackEndpoint? = null)

object UsbPlaybackPlans {
    fun select(description: UsbAudioDescription, endpoints: List<UsbPlaybackEndpoint>, rate: Int, bits: Int, speed: Int): UsbPlaybackPlan {
        require(rate > 0 && rate <= 384000 && bits in setOf(16, 24, 32))
        require(description.warnings.isEmpty()) { "Повреждено описание USB. Используй вывод Android." }
        require(speed == 2 || speed == 3) { "Скорость USB пока не поддерживается напрямую. Используй вывод Android." }
        val candidates = mutableListOf<UsbPlaybackPlan>()
        for (format in description.formats.filter { it.version == 0x200 && it.pcm && it.channels == 2 && it.bits == bits && it.bytesPerSample == bits / 8 }) {
            val alternateEndpoints = endpoints.filter { it.iface == format.iface && it.alternate == format.alternate }
            val ep = alternateEndpoints.singleOrNull { it.address in 1..15 } ?: continue
            if (ep.attributes and 3 != 1 || ep.attributes and 0x30 != 0 || (ep.attributes shr 2 and 3) !in 1..3) continue
            val async = ep.attributes shr 2 and 3 == 1
            val feedback = if (async) alternateEndpoints.singleOrNull { it.address in 0x81..0x8f && it.attributes == 0x11 } else null
            if (async && (feedback == null || alternateEndpoints.size != 2 || feedback.interval !in 1..8 || feedback.maxPacket !in (if (speed == 3) 4..4 else 3..4))) continue
            if (!async && alternateEndpoints.size != 1) continue
            if (ep.interval !in 1..4 || speed == 2 && ep.interval != 1 || ep.maxPacket !in 1..1024) continue
            if (speed == 2 && ep.maxPacket > 1023) continue
            val intervals = (if (speed == 3) 8000 else 1000) / (1 shl (ep.interval - 1))
            if (((rate + intervals - 1) / intervals + if (async) 1 else 0) * (bits / 8 * 2) > ep.maxPacket) continue
            for (input in description.nodes.filter { it.input && it.id == format.terminal && it.terminalType == 0x101 }) {
                val nodes = description.nodes.filter { it.iface == input.iface }
                if (nodes.groupBy { it.id }.any { it.value.size != 1 }) continue
                val clock = description.clocks.singleOrNull { it.controlInterface == input.iface && it.id == input.clock && it.controls and 3 == 3 } ?: continue
                for (output in nodes.filter { it.output && it.terminalType in 0x300..0x7ff && it.clock == clock.id }) {
                    val path = mutableListOf<UsbAudioNode>(); val seen = mutableSetOf<Int>(); var current: UsbAudioNode? = output
                    while (current != null && seen.add(current.id)) {
                        path += current
                        if (current.id == input.id) break
                        current = nodes.singleOrNull { it.id == current.source }
                    }
                    if (path.lastOrNull()?.id != input.id) continue
                    val units = path.drop(1).dropLast(1).map { it.id }
                    val usable = units.mapNotNull { unit ->
                        val volumes = description.volumes.filter { it.controlInterface == input.iface && it.unit == unit && it.version == 0x200 && it.writable }.map { it.channel }.toSet()
                        when { 0 in volumes -> unit to listOf(0); 1 in volumes && 2 in volumes -> unit to listOf(1, 2); else -> null }
                    }
                    // Chained gain stages need explicit coordination before they can be supported.
                    if (usable.size == 1 && units.size == 1) candidates += UsbPlaybackPlan(format, ep, clock, usable.single().first, usable.single().second, feedback)
                }
            }
        }
        return candidates.distinct().singleOrNull() ?: error("Этот USB-профиль пока не поддерживается напрямую. Используй вывод Android.")
    }
}
