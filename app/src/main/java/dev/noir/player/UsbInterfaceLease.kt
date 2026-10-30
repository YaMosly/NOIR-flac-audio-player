package dev.noir.player

/** Releases every attempted interface even when acquiring a later interface fails. */
class UsbInterfaceLease(
    private val acquire: (Int) -> Boolean,
    private val release: (Int) -> Boolean,
    private val note: (String) -> Unit,
) : AutoCloseable {
    private val attempted = mutableListOf<Int>()
    private var closed = false

    fun claim(interfaces: List<Int>) {
        check(!closed)
        check(attempted.isEmpty()) { "Интерфейсы уже запрашивались" }
        require(interfaces.size in 1..4 && interfaces.distinct().size == interfaces.size)
        require(interfaces.all { it in 0..255 })
        interfaces.forEach { id ->
            attempted += id
            val acquired = acquire(id)
            note("Interface $id: claim(force=true) = $acquired")
            check(acquired) { "Не удалось получить эксклюзивный доступ к интерфейсу $id" }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        attempted.asReversed().forEach { id ->
            val result = runCatching { release(id) }
            note("Interface $id: release = ${result.getOrDefault(false)}")
            if (result.getOrDefault(false).not()) note("Возврат интерфейса $id не подтверждён. Переподключи ЦАП перед воспроизведением.")
        }
    }
}
