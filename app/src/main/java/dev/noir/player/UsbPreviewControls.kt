package dev.noir.player

/** Saves every original value before any SET. Defaults preserve the legacy diagnostic probe. */
class UsbPreviewControls(
    private val read: (selector: Int, entity: Int, channel: Int, request: Int, length: Int) -> ByteArray,
    private val write: (selector: Int, entity: Int, channel: Int, value: ByteArray) -> Unit,
    private val note: (String) -> Unit,
    private val clockId: Int = 6,
    private val volumeUnit: Int = 7,
    private val channels: List<Int> = listOf(1, 2),
) {
    private val volumeRanges = mutableMapOf<Int, List<UsbRange>>()
    fun volumeValue(target: Int): Int {
        check(volumeRanges.size == channels.size)
        if (target == -32768) return target
        return (target.coerceAtMost(0) downTo -32767).firstOrNull { value ->
            volumeRanges.values.all { ranges -> ranges.any { it.contains(value.toLong()) } }
        } ?: throw IllegalArgumentException("ЦАП не поддерживает выбранный уровень громкости")
    }
    private var originalClock: ByteArray? = null
    private val originalVolumes = linkedMapOf<Int, ByteArray>()
    private val touchedVolumes = linkedSetOf<Int>()
    private var touchedClock = false

    fun prepare(db: Int, sampleRate: Int = 44_100) {
        require(db in -45..-15 && db % 5 == 0)
        check(originalClock == null)
        fun ranges(selector: Int, entity: Int, channel: Int, width: Int, signed: Boolean): List<UsbRange> {
            val head = read(selector, entity, channel, 2, 2)
            require(head.size == 2)
            val count = UsbControlValues.unsigned(head).toInt()
            require(count in 1..64) { "Некорректный RANGE USB" }
            return UsbControlValues.ranges(read(selector, entity, channel, 2, 2 + count * width * 3), width, signed)
        }
        val clock = read(1, clockId, 0, 1, 4).also { require(it.size == 4) }
        val rates = ranges(1, clockId, 0, 4, false)
        require(rates.any { it.contains(sampleRate.toLong()) } && rates.any { it.contains(UsbControlValues.unsigned(clock)) }) { "Частоты USB не подтверждены" }
        for (channel in channels) {
            originalVolumes[channel] = read(2, volumeUnit, channel, 1, 2).also { require(it.size == 2) }
            val supported = ranges(2, volumeUnit, channel, 2, true)
            volumeRanges[channel] = supported
            val original = UsbControlValues.signed16(originalVolumes.getValue(channel)).toLong()
            require(original == -32768L || supported.any { it.contains(original) }) { "Диапазон громкости USB не подтверждён" }
        }
        val target = volumeValue(db * 256)
        originalClock = clock
        for (channel in channels) {
            touchedVolumes += channel // A failed SET may already have affected hardware.
            val bytes = byteArrayOf(target.toByte(), (target shr 8).toByte())
            write(2, volumeUnit, channel, bytes)
            check(read(2, volumeUnit, channel, 1, 2).contentEquals(bytes)) { "ЦАП не подтвердил тихий уровень канала $channel. Передача отменена." }
        }
        touchedClock = true
        val rate = ByteArray(4) { (sampleRate ushr (it * 8)).toByte() }
        write(1, clockId, 0, rate)
        check(read(1, clockId, 0, 1, 4).contentEquals(rate)) { "ЦАП не подтвердил $sampleRate Гц" }
        note("Clock $clockId: SET + GET = $sampleRate Гц; Unit $volumeUnit: SET + GET = ${target / 256.0} дБ; каналы $channels")
    }

    fun restore(streamStopped: Boolean) {
        // Never raise the level if the inactive alternate setting could not be confirmed.
        if (!streamStopped) { note("Не подтверждён alt 0: громкость оставлена пониженной. Переподключи ЦАП."); return }
        fun attempt(label: String, operation: () -> Unit) { runCatching(operation).onFailure { note("$label: ошибка — ${it.message}") } }
        if (touchedClock) attempt("Восстановление частоты") {
            val value = originalClock!!
            write(1, clockId, 0, value)
            check(read(1, clockId, 0, 1, 4).contentEquals(value))
            note("Clock $clockId: восстановлено ${UsbControlValues.unsigned(value)} Гц")
        }
        for (channel in touchedVolumes.toList().asReversed()) attempt("Восстановление громкости ch=$channel") {
            val value = originalVolumes.getValue(channel)
            write(2, volumeUnit, channel, value)
            check(read(2, volumeUnit, channel, 1, 2).contentEquals(value))
            note("Volume ch=$channel: восстановлено ${UsbControlValues.signed16(value) / 256.0} дБ")
        }
    }
}
