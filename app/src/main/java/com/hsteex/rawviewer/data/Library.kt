package com.hsteex.rawviewer.data

enum class FlagFilter(val label: String) {
    ALL("Tutte"), UNRATED("Da valutare"), PICK("✓ Scarica"), REJECT("✗ Scartate"), NOT_REJECTED("Non scartate"), DOWNLOADED("⬇ Scaricate")
}

enum class TypeFilter(val label: String) {
    ALL("Tutti i tipi"), BOTH("RAW+JPG"), JPEG_ONLY("Solo JPG"), RAW_ONLY("Solo RAW"), HAS_RAW("Con RAW")
}

enum class Sort(val label: String) {
    TIME_ASC("Data ↑ (vecchie prima)"), TIME_DESC("Data ↓ (recenti prima)"), NAME_ASC("Nome A→Z"), NAME_DESC("Nome Z→A")
}

enum class OrientFilter(val label: String) { ALL("Tutte"), LANDSCAPE("Orizzontali"), PORTRAIT("Verticali") }

data class Filter(
    val flag: FlagFilter = FlagFilter.ALL,
    val minStars: Int = 0,
    val type: TypeFilter = TypeFilter.ALL,
    val folder: String? = null,
    val sort: Sort = Sort.TIME_ASC,
    val groupBursts: Boolean = false,
    // filtri avanzati (sui metadati): insiemi vuoti = nessun vincolo
    val films: Set<String> = emptySet(),
    val lenses: Set<String> = emptySet(),
    val orient: OrientFilter = OrientFilter.ALL,
    val isoMax: Int? = null,
) {
    /** Quanti filtri avanzati sono attivi. */
    val advancedCount: Int
        get() = (if (films.isNotEmpty()) 1 else 0) + (if (lenses.isNotEmpty()) 1 else 0) +
            (if (orient != OrientFilter.ALL) 1 else 0) + (if (isoMax != null) 1 else 0)

    val isDefault: Boolean
        get() = flag == FlagFilter.ALL && minStars == 0 && type == TypeFilter.ALL && folder == null && advancedCount == 0

    fun clearAdvanced() = copy(films = emptySet(), lenses = emptySet(), orient = OrientFilter.ALL, isoMax = null)
}

sealed interface GridItem {
    val key: String

    data class Single(val shot: Shot) : GridItem {
        override val key get() = shot.key
    }

    data class Burst(val shots: List<Shot>) : GridItem {
        override val key get() = "burst:" + shots.first().key
        val cover: Shot get() = shots.first()
    }
}

object Library {

    /**
     * Ora di scatto. Se i metadati non sono ancora stati letti si usa la data del file, corretta di
     * [mtimeOffset]: sulle SD (FAT) è in ora locale letta come UTC, quindi sfasata del fuso orario,
     * e senza correzione gli scatti non ancora indicizzati finirebbero fuori posto.
     */
    fun time(s: Shot, metas: Map<String, PhotoMeta>, mtimeOffset: Long = 0): Long =
        metas[s.key]?.dateTaken ?: (s.modified + mtimeOffset)

    /** Scarto tipico (mediana) tra ora di scatto e data del file, misurato sugli scatti già indicizzati. */
    fun mtimeOffset(shots: List<Shot>, metas: Map<String, PhotoMeta>): Long {
        if (metas.isEmpty()) return 0
        val diffs = ArrayList<Long>()
        val step = (shots.size / 400).coerceAtLeast(1)
        var i = 0
        while (i < shots.size) {
            val s = shots[i]
            metas[s.key]?.dateTaken?.let { diffs += it - s.modified }
            i += step
        }
        if (diffs.isEmpty()) return 0
        diffs.sort()
        return diffs[diffs.size / 2]
    }

    fun apply(
        shots: List<Shot>,
        metas: Map<String, PhotoMeta>,
        ratings: Map<String, Rating>,
        f: Filter,
    ): List<Shot> {
        val out = shots.filter { s ->
            val r = ratings[s.key] ?: Rating()
            val flagOk = when (f.flag) {
                FlagFilter.ALL -> true
                FlagFilter.UNRATED -> r.isUnrated && !r.downloaded
                FlagFilter.PICK -> r.flag == Flag.PICK
                FlagFilter.REJECT -> r.flag == Flag.REJECT
                FlagFilter.NOT_REJECTED -> r.flag != Flag.REJECT
                FlagFilter.DOWNLOADED -> r.downloaded
            }
            val typeOk = when (f.type) {
                TypeFilter.ALL -> true
                TypeFilter.BOTH -> s.jpeg != null && s.raw != null
                TypeFilter.JPEG_ONLY -> s.jpeg != null && s.raw == null
                TypeFilter.RAW_ONLY -> s.jpeg == null && s.raw != null
                TypeFilter.HAS_RAW -> s.raw != null
            }
            flagOk && typeOk && r.stars >= f.minStars && (f.folder == null || s.relDir == f.folder) &&
                (f.advancedCount == 0 || advancedOk(metas[s.key], f))
        }
        val off = mtimeOffset(shots, metas)
        return when (f.sort) {
            Sort.TIME_ASC -> out.sortedWith(compareBy<Shot>({ time(it, metas, off) }, { it.relDir }, { it.baseName }))
            Sort.TIME_DESC -> out.sortedWith(
                compareByDescending<Shot> { time(it, metas, off) }.thenByDescending { it.relDir }.thenByDescending { it.baseName }
            )
            Sort.NAME_ASC -> out.sortedWith(compareBy({ it.relDir }, { it.baseName }))
            Sort.NAME_DESC -> out.sortedWith(compareByDescending<Shot> { it.relDir }.thenByDescending { it.baseName })
        }
    }

    private fun advancedOk(m: PhotoMeta?, f: Filter): Boolean {
        if (m == null) return false // metadati non ancora letti: si escludono finché non arrivano
        if (f.films.isNotEmpty() && (m.film ?: NO_FILM) !in f.films) return false
        if (f.lenses.isNotEmpty() && lensName(m) !in f.lenses) return false
        if (f.isoMax != null && (m.iso ?: Int.MAX_VALUE) > f.isoMax) return false
        if (f.orient != OrientFilter.ALL) {
            val w = m.width ?: return false
            val h = m.height ?: return false
            val rotated = m.orientation in 5..8
            val landscape = if (rotated) h >= w else w >= h
            if ((f.orient == OrientFilter.LANDSCAPE) != landscape) return false
        }
        return true
    }

    const val NO_FILM = "Nessuna / non Fuji"

    /** Obiettivo leggibile: nome EXIF, altrimenti la focale (compatte e obiettivi senza contatti). */
    fun lensName(m: PhotoMeta): String =
        m.lens?.trim()?.ifEmpty { null } ?: (m.focal?.let { "%.0f mm".format(it) } ?: "Sconosciuto")

    /**
     * Raffiche: scatti consecutivi (nella stessa cartella) distanti al massimo [gapMs].
     * Servono almeno 3 scatti per formare una pila.
     */
    fun bursts(sorted: List<Shot>, metas: Map<String, PhotoMeta>, gapMs: Long, enabled: Boolean): List<GridItem> {
        if (!enabled || sorted.size < 3) return sorted.map { GridItem.Single(it) }
        val off = mtimeOffset(sorted, metas)
        val out = ArrayList<GridItem>()
        var run = ArrayList<Shot>()
        fun flush() {
            if (run.size >= 3) out += GridItem.Burst(run) else run.forEach { out += GridItem.Single(it) }
            run = ArrayList()
        }
        for (s in sorted) {
            val prev = run.lastOrNull()
            if (prev != null &&
                (prev.relDir != s.relDir || kotlin.math.abs(time(s, metas, off) - time(prev, metas, off)) > gapMs)
            ) flush()
            run += s
        }
        flush()
        return out
    }
}
