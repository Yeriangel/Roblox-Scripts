package com.diagnosepro.app.data

import com.diagnosepro.app.elm327.RawLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class RawLogLine(val time: Long, val direction: RawLogger.Direction, val text: String)

/** Ring buffer of every raw ELM327 command and response, for the developer console. */
@Singleton
class RawLogStore @Inject constructor() : RawLogger {
    private val max = 2_000
    private val buffer = ArrayDeque<RawLogLine>()
    private val _lines = MutableStateFlow<List<RawLogLine>>(emptyList())
    val lines: StateFlow<List<RawLogLine>> = _lines
    @Volatile var enabled: Boolean = true

    override fun log(direction: RawLogger.Direction, text: String) {
        if (!enabled) return
        synchronized(buffer) {
            buffer.addLast(RawLogLine(System.currentTimeMillis(), direction, text))
            while (buffer.size > max) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
    }

    fun clear() = synchronized(buffer) { buffer.clear(); _lines.value = emptyList() }

    fun asText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _lines.value.joinToString("\n") { "${fmt.format(Date(it.time))} ${it.direction.name.padEnd(4)} ${it.text}" }
    }
}
