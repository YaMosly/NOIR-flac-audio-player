package dev.noir.player

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FlacInfo(val rate: Int, val bits: Int, val channels: Int, val durationMs: Long, val tags: Map<String, String>, val picture: ByteArray?)

/** Reads bounded metadata blocks only. Audio frames are never loaded into memory. */
object FlacMetadata {
    fun read(source: InputStream, includePicture: Boolean = false): FlacInfo {
        val input = DataInputStream(source)
        require(input.readInt() == 0x664C6143) { "Файл не содержит заголовок FLAC" }
        var rate = 0; var bits = 0; var channels = 0; var duration = 0L
        var picture: ByteArray? = null
        val tags = mutableMapOf<String, String>()
        var total = 0L
        var first = true
        do {
            val header = input.readUnsignedByte()
            val type = header and 127
            val size = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            total += size + 4L
            require(total <= 64L * 1024 * 1024) { "Слишком большой блок метаданных" }
            if (first) require(type == 0 && size == 34) { "Некорректный STREAMINFO" }
            first = false
            when {
                type == 0 -> {
                    require(size == 34)
                    val block = ByteArray(size); input.readFully(block)
                    val packed = ByteBuffer.wrap(block, 10, 8).long
                    rate = ((packed ushr 44) and 0xfffff).toInt()
                    channels = (((packed ushr 41) and 7) + 1).toInt()
                    bits = (((packed ushr 36) and 31) + 1).toInt()
                    require(rate > 0 && bits >= 4)
                    duration = ((packed and 0xfffffffffL) * 1000) / rate
                }
                type == 4 && size <= 2 * 1024 * 1024 -> {
                    val block = ByteArray(size); input.readFully(block)
                    val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
                    fun length(): Int {
                        require(buffer.remaining() >= 4)
                        return buffer.int.also { require(it >= 0 && it <= buffer.remaining()) }
                    }
                    val vendorLength = length()
                    buffer.position(buffer.position() + vendorLength)
                    require(buffer.remaining() >= 4)
                    val count = buffer.int
                    require(count in 0..10000)
                    repeat(count) {
                        val value = ByteArray(length()); buffer.get(value)
                        val text = value.toString(Charsets.UTF_8)
                        val split = text.indexOf('=')
                        if (split > 0) tags.putIfAbsent(text.substring(0, split).uppercase(java.util.Locale.ROOT), text.substring(split + 1))
                    }
                }
                type == 6 && includePicture && size <= 12 * 1024 * 1024 -> {
                    val block = ByteArray(size); input.readFully(block)
                    val b = ByteBuffer.wrap(block)
                    fun skipString() { require(b.remaining() >= 4); val n = b.int; require(n >= 0 && n <= b.remaining()); b.position(b.position() + n) }
                    require(b.remaining() >= 4)
                    val pictureType = b.int
                    skipString(); skipString()
                    require(b.remaining() >= 20); b.position(b.position() + 16)
                    val n = b.int; require(n >= 0 && n <= b.remaining())
                    if (picture == null || pictureType == 3) picture = ByteArray(n).also { b.get(it) }
                }
                else -> input.skipExactly(size)
            }
            if ((header and 128) != 0) break
        } while (true)
        return FlacInfo(rate, bits, channels, duration, tags, picture)
    }

    private fun InputStream.skipExactly(size: Int) {
        var left = size.toLong()
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) left -= skipped else { if (read() == -1) throw EOFException(); left-- }
        }
    }
}
