package com.satarkai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

object UiDebugLogger {
    private const val MAX_LINES = 120
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(message: String) {
        val line = "${formatter.format(Date())}  $message"
        lines.addLast(line)
        while (lines.size > MAX_LINES) {
            lines.removeFirst()
        }
        android.util.Log.d("SatarkAI", message)
        listeners.forEach { it.invoke(line) }
    }

    @Synchronized
    fun snapshot(): String {
        return lines.joinToString(separator = "\n")
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
