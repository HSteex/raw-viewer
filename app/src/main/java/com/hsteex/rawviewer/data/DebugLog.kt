package com.hsteex.rawviewer.data

import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro di debug dentro l'app: l'utente lo attiva dalle impostazioni, usa l'app, poi copia o
 * condivide il testo. Sempre anche su logcat (tag "RV/…"); in memoria solo se attivo, ultime [MAX] righe.
 * Le righe hanno l'ora al millisecondo e il thread, così si ricostruiscono tempi e sovrapposizioni.
 */
object DebugLog {
    private const val MAX = 4000

    @Volatile
    var enabled: Boolean = false

    private val lines = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _count = MutableStateFlow(0)
    /** Cambia a ogni nuova riga: serve alla schermata del registro per aggiornarsi. */
    val count: StateFlow<Int> = _count.asStateFlow()

    fun d(tag: String, msg: String) {
        Log.d("RV/$tag", msg)
        if (enabled) add(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w("RV/$tag", msg, t)
        if (enabled) add(tag, "⚠ $msg" + (t?.let { " · ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    /** Misura [block] e scrive "[label] N ms" (anche se lancia un'eccezione). */
    inline fun <T> time(tag: String, label: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            d(tag, "$label ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    private fun add(tag: String, msg: String) {
        synchronized(lines) {
            val line = "${fmt.format(Date())} ${Thread.currentThread().name.takeLast(14).padEnd(14)} $tag: $msg"
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(line)
            _count.value = _count.value + 1
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        _count.value = 0
    }

    /** Testo completo da condividere: intestazione col dispositivo, poi tutte le righe. */
    fun dump(appVersion: String, maxMemMb: Long): String = buildString {
        appendLine("RAW Viewer $appVersion · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("heap max ${maxMemMb} MB · cpu ${Runtime.getRuntime().availableProcessors()} core")
        appendLine("----")
        snapshot().forEach { appendLine(it) }
    }
}
