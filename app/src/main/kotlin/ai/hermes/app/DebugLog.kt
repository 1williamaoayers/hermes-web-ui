package ai.hermes.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object DebugLog {
    private const val MAX_ENTRIES = 500
    private val entries = ConcurrentLinkedQueue<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @JvmStatic
    fun log(tag: String, level: String, msg: String) {
        val ts = timeFormat.format(Date())
        val entry = "[$ts] $level/$tag: $msg"
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) entries.poll()
        when (level) {
            "E" -> Log.e(tag, msg)
            "W" -> Log.w(tag, msg)
            "D" -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }
    }

    fun snapshot(): String = entries.joinToString("\n")

    fun clear() = entries.clear()
}
