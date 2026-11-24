package com.hsteex.rawviewer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _autoAdvance = MutableStateFlow(sp.getBoolean("autoAdvance", true))
    val autoAdvance: StateFlow<Boolean> = _autoAdvance.asStateFlow()

    private val _swipe = MutableStateFlow(sp.getBoolean("swipe", true))
    val swipeGestures: StateFlow<Boolean> = _swipe.asStateFlow()

    private val _columns = MutableStateFlow(sp.getInt("columns", 3))
    val columns: StateFlow<Int> = _columns.asStateFlow()

    private val _burstGap = MutableStateFlow(sp.getLong("burstGap", 1500L))
    val burstGapMs: StateFlow<Long> = _burstGap.asStateFlow()

    private val _timings = MutableStateFlow(sp.getBoolean("timings", false))
    val showTimings: StateFlow<Boolean> = _timings.asStateFlow()

    private val _importFormat = MutableStateFlow(
        runCatching { ImportFormat.valueOf(sp.getString("importFormat", null) ?: "JPEG") }.getOrDefault(ImportFormat.JPEG)
    )
    val importFormat: StateFlow<ImportFormat> = _importFormat.asStateFlow()

    fun setAutoAdvance(v: Boolean) { _autoAdvance.value = v; sp.edit().putBoolean("autoAdvance", v).apply() }
    fun setSwipe(v: Boolean) { _swipe.value = v; sp.edit().putBoolean("swipe", v).apply() }
    fun setColumns(v: Int) { _columns.value = v.coerceIn(2, 6); sp.edit().putInt("columns", _columns.value).apply() }
    fun setBurstGap(v: Long) { _burstGap.value = v; sp.edit().putLong("burstGap", v).apply() }
    /** Id dell'ultimo lavoro di copia il cui esito è già stato visto (il banner non torna). */
    var dismissedImport: String?
        get() = sp.getString("dismissedImport", null)
        set(v) { sp.edit().putString("dismissedImport", v).apply() }

    fun setShowTimings(v: Boolean) { _timings.value = v; sp.edit().putBoolean("timings", v).apply() }
    fun setImportFormat(v: ImportFormat) { _importFormat.value = v; sp.edit().putString("importFormat", v.name).apply() }
}
