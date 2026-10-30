package dev.noir.player

/** Bounded parser for USB Audio 1/2 descriptors. It never interprets vendor payloads or serial strings. */
data class UsbClock(val controlInterface: Int, val id: Int, val controls: Int)
data class UsbVolume(val controlInterface: Int, val version: Int, val unit: Int, val channel: Int, val writable: Boolean)
data class UsbStreamFormat(val iface: Int, val alternate: Int, val version: Int, val channels: Int, val bits: Int, val bytesPerSample: Int, val rates: List<Long>, val continuous: Pair<Long, Long>? = null)
data class UsbAudioDescription(val versions: Set<Int>, val clocks: List<UsbClock>, val volumes: List<UsbVolume>, val formats: List<UsbStreamFormat>, val warnings: List<String>)

object UsbAudioDescriptors {
    fun parse(raw: ByteArray): UsbAudioDescription {
        require(raw.size <= 256 * 1024) { "Слишком большое описание USB" }
        val versions = mutableSetOf<Int>()
        val clocks = mutableListOf<UsbClock>()
        val volumes = mutableListOf<UsbVolume>()
        val formats = mutableListOf<UsbStreamFormat>()
        val warnings = mutableListOf<String>()
        var offset = 0; var iface = -1; var alternate = 0; var audio = false; var subclass = 0; var version = 0; var channels = 0
        fun u8(at: Int) = raw[at].toInt() and 255
        fun le(at: Int, bytes: Int): Long = (0 until bytes).fold(0L) { value, i -> value or (u8(at + i).toLong() shl (8 * i)) }
        while (offset < raw.size) {
            if (raw.size - offset < 2) { warnings += "Неполный заголовок USB"; break }
            val size = u8(offset)
            if (size < 2 || offset + size > raw.size) { warnings += "Повреждён или обрезан USB-дескриптор"; break }
            val type = u8(offset + 1)
            fun b(at: Int) = u8(offset + at)
            when {
                type == 4 && size >= 9 -> {
                    iface = b(2); alternate = b(3); audio = b(5) == 1; subclass = b(6)
                    version = when (b(7)) { 0x20 -> 0x200; 0x30 -> 0x300; else -> 0x100 }
                    channels = 0
                    if (audio) versions += version
                }
                type == 0x24 && audio && size >= 3 -> {
                    val subtype = b(2)
                    if (subclass == 1 && subtype == 1 && size >= 5) {
                        val headerVersion = le(offset + 3, 2).toInt()
                        if (headerVersion in 0x100..0x3ff) { versions.remove(version); version = headerVersion; versions += version }
                    }
                    if (subclass == 1 && version in 0x200..0x2ff && subtype == 0x0a && size >= 8) {
                        clocks += UsbClock(iface, b(3), b(5))
                    }
                    if (subclass == 1 && subtype == 6) {
                        if (version in 0x200..0x2ff && size >= 10) {
                            val count = ((size - 6) / 4).coerceAtMost(33)
                            repeat(count) { channel ->
                                val controls = le(offset + 5 + channel * 4, 4)
                                val volumeBits = ((controls ushr 2) and 3).toInt()
                                if (volumeBits == 1 || volumeBits == 3) volumes += UsbVolume(iface, version, b(3), channel, volumeBits == 3)
                            }
                        } else if (version in 0x100..0x1ff && size >= 8) {
                            val controlSize = b(5)
                            if (controlSize in 1..4) {
                                val count = ((size - 7) / controlSize).coerceAtMost(33)
                                repeat(count) { channel ->
                                    if ((le(offset + 6 + channel * controlSize, controlSize) and 2) != 0L) volumes += UsbVolume(iface, version, b(3), channel, true)
                                }
                            }
                        }
                    }
                    if (subclass == 2 && version in 0x200..0x2ff && subtype == 1 && size >= 16) channels = b(10)
                    if (subclass == 2 && subtype == 2 && size >= 4 && b(3) == 1) {
                        if (version in 0x100..0x1ff && size >= 8) {
                            val count = b(7)
                            if (count == 0 && size >= 14) {
                                formats += UsbStreamFormat(iface, alternate, version, b(4), b(6), b(5), emptyList(), le(offset + 8, 3) to le(offset + 11, 3))
                            } else if (count > 0 && size >= 8 + 3 * count) {
                                formats += UsbStreamFormat(iface, alternate, version, b(4), b(6), b(5), (0 until count).map { le(offset + 8 + it * 3, 3) })
                            } else warnings += "Неполное описание частот UAC1"
                        } else if (version in 0x200..0x2ff && size >= 6) {
                            formats += UsbStreamFormat(iface, alternate, version, channels, b(5), b(4), emptyList())
                        }
                    }
                }
            }
            offset += size
        }
        return UsbAudioDescription(versions, clocks.distinct(), volumes.distinct(), formats.distinct(), warnings.distinct())
    }
}

data class UsbRange(val min: Long, val max: Long, val step: Long) {
    fun contains(value: Long): Boolean = value in min..max && (step == 0L || (value - min) % step == 0L)
}

object UsbControlValues {
    fun unsigned(bytes: ByteArray): Long = bytes.foldIndexed(0L) { i, n, b -> n or ((b.toLong() and 255) shl (8 * i)) }
    fun signed16(bytes: ByteArray): Int { require(bytes.size == 2); return unsigned(bytes).toShort().toInt() }
    fun ranges(bytes: ByteArray, width: Int, signed: Boolean): List<UsbRange> {
        require(width == 2 || width == 4)
        require(bytes.size >= 2)
        val count = unsigned(bytes.copyOfRange(0, 2)).toInt()
        require(count <= 64 && bytes.size >= 2 + count * 3 * width) { "Неполный ответ RANGE" }
        fun value(at: Int): Long {
            val part = bytes.copyOfRange(at, at + width)
            return if (signed && width == 2) signed16(part).toLong() else unsigned(part)
        }
        return (0 until count).map { i ->
            val start = 2 + i * 3 * width
            UsbRange(value(start), value(start + width), value(start + width * 2)).also { require(it.min <= it.max && it.step >= 0) }
        }
    }
}

/** Only device-to-host requests are representable by this interface. No SET requests exist here. */
fun interface UsbReadTransport {
    fun read(request: Int, selector: Int, entity: Int, iface: Int, channel: Int, length: Int): ByteArray?
}

object UsbReadProbe {
    fun report(description: UsbAudioDescription, transport: UsbReadTransport, exclusive: Boolean = false): String = buildString {
        appendLine("USB Audio: ${description.versions.joinToString { "${it ushr 8}.${(it and 255).toString(16)}" }.ifBlank { "не распознано" }}")
        description.formats.forEach { format ->
            append("AS ${format.iface}, alt ${format.alternate}: ${format.channels} каналов, ${format.bits} бит в ${format.bytesPerSample} байтах")
            if (format.rates.isNotEmpty()) append("; Гц ${format.rates}")
            format.continuous?.let { append("; непрерывный диапазон ${it.first}..${it.second} Гц") }
            appendLine()
        }
        if (description.clocks.isEmpty() && description.versions.any { it in 0x200..0x2ff }) appendLine("Источники тактирования UAC2 не найдены в описании")
        description.clocks.take(16).forEach { clock ->
            appendLine("Clock ${clock.id}, AC ${clock.controlInterface}:")
            if ((clock.controls and 1) == 0) { appendLine("  Чтение частоты не заявлено"); return@forEach }
            val current = transport.read(1, 1, clock.id, clock.controlInterface, 0, 4)?.takeIf { it.size == 4 }?.let(UsbControlValues::unsigned)
            appendLine("  CUR: ${current?.let { "$it Гц" } ?: "ответ не получен"}")
            val head = transport.read(2, 1, clock.id, clock.controlInterface, 0, 2)
            val count = head?.takeIf { it.size == 2 }?.let(UsbControlValues::unsigned)?.toInt()
            val ranges = if (count != null && count in 1..64) {
                val response = transport.read(2, 1, clock.id, clock.controlInterface, 0, 2 + count * 12)
                response?.let { runCatching { UsbControlValues.ranges(it, 4, false) }.getOrNull() }
            } else null
            appendLine("  RANGE: ${ranges?.joinToString { "${it.min}..${it.max} шаг ${it.step}" } ?: "ответ не получен"}")
            appendLine("  44100 Гц в RANGE: ${ranges?.any { it.contains(44_100) } ?: "неизвестно"}")
        }
        if (description.volumes.isEmpty()) appendLine("Регуляторы громкости USB не найдены в распознанных Feature Unit")
        description.volumes.take(16).forEach { volume ->
            appendLine("Volume: AC ${volume.controlInterface}, Unit ${volume.unit}, канал ${volume.channel}, запись заявлена: ${volume.writable}")
            val uac2 = volume.version in 0x200..0x2ff
            val current = transport.read(if (uac2) 1 else 0x81, 2, volume.unit, volume.controlInterface, volume.channel, 2)
            fun db(data: ByteArray?): String = data?.takeIf { it.size == 2 }?.let { val n = UsbControlValues.signed16(it); if (n == -32768) "−∞ дБ" else "${n / 256.0} дБ" } ?: "ответ не получен"
            appendLine("  CUR: ${db(current)}")
            if (uac2) {
                val head = transport.read(2, 2, volume.unit, volume.controlInterface, volume.channel, 2)
                val count = head?.takeIf { it.size == 2 }?.let(UsbControlValues::unsigned)?.toInt()
                val ranges = if (count != null && count in 1..64) transport.read(2, 2, volume.unit, volume.controlInterface, volume.channel, 2 + count * 6)?.let { runCatching { UsbControlValues.ranges(it, 2, true) }.getOrNull() } else null
                appendLine("  RANGE: ${ranges?.joinToString { "${it.min / 256.0}..${it.max / 256.0} дБ; шаг ${it.step / 256.0}" } ?: "ответ не получен"}")
            } else {
                appendLine("  MIN: ${db(transport.read(0x82, 2, volume.unit, volume.controlInterface, volume.channel, 2))}")
                appendLine("  MAX: ${db(transport.read(0x83, 2, volume.unit, volume.controlInterface, volume.channel, 2))}")
                appendLine("  RES: ${db(transport.read(0x84, 2, volume.unit, volume.controlInterface, volume.channel, 2))}")
            }
        }
        description.warnings.forEach { appendLine("Замечание: $it") }
        appendLine("CUR относится к указанному Clock/Unit в момент чтения и не подтверждает активный путь или bit-perfect.")
        appendLine("NOIR не отправлял команд изменения частоты, громкости или USB-конфигурации.")
        appendLine(if (exclusive) "Эксклюзивная проверка: системный USB-драйвер мог временно отключаться; интерфейсы освобождаются после чтения." else "Обычная проверка: принудительное отключение системного драйвера не выполнялось.")
    }
}
