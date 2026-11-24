package com.hsteex.rawviewer.data

enum class FlagFilter(val label: String) {
    ALL("Tutte"), UNRATED("Da valutare"), PICK("✓ Scarica"), REJECT("✗ Scartate"), NOT_REJECTED("Non scartate")
}

enum class TypeFilter(val label: String) {
    ALL("Tutti i tipi"), BOTH("RAW+JPG"), JPEG_ONLY("Solo JPG"), RAW_ONLY("Solo RAW"), HAS_RAW("Con RAW")
}

enum class Sort(val label: String) {
    TIME_ASC("Data ↑ (vecchie prima)"), TIME_DESC("Data ↓ (recenti prima)"), NAME_ASC("Nome A→Z"), NAME_DESC("Nome Z→A")
}

data class Filter(
    val flag: FlagFilter = FlagFilter.ALL,
    val minStars: Int = 0,
    val type: TypeFilter = TypeFilter.ALL,
    val folder: String? = null,
    val sort: Sort = Sort.TIME_ASC,
    val groupBursts: Boolean = false,
) {
    val isDefault: Boolean
        get() = flag == FlagFilter.ALL && minStars == 0 && type == TypeFilter.ALL && folder == null
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

    fun time(s: Shot, metas: Map<String, PhotoMeta>): Long = metas[s.key]?.dateTaken ?: s.modified

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
                FlagFilter.UNRATED -> r.isEmpty
                FlagFilter.PICK -> r.flag == Flag.PICK
                FlagFilter.REJECT -> r.flag == Flag.REJECT
                FlagFilter.NOT_REJECTED -> r.flag != Flag.REJECT
            }
            val typeOk = when (f.type) {
                TypeFilter.ALL -> true
                TypeFilter.BOTH -> s.jpeg != null && s.raw != null
                TypeFilter.JPEG_ONLY -> s.jpeg != null && s.raw == null
                TypeFilter.RAW_ONLY -> s.jpeg == null && s.raw != null
                TypeFilter.HAS_RAW -> s.raw != null
            }
            flagOk && typeOk && r.stars >= f.minStars && (f.folder == null || s.relDir == f.folder)
        }
        return when (f.sort) {
            Sort.TIME_ASC -> out.sortedWith(compareBy<Shot>({ time(it, metas) }, { it.baseName }))
            Sort.TIME_DESC -> out.sortedWith(compareByDescending<Shot> { time(it, metas) }.thenByDescending { it.baseName })
            Sort.NAME_ASC -> out.sortedWith(compareBy({ it.relDir }, { it.baseName }))
            Sort.NAME_DESC -> out.sortedWith(compareByDescending<Shot> { it.relDir }.thenByDescending { it.baseName })
        }
    }

    /**
     * Raffiche: scatti consecutivi (nella stessa cartella) distanti al massimo [gapMs].
     * Servono almeno 3 scatti per formare una pila.
     */
    fun bursts(sorted: List<Shot>, metas: Map<String, PhotoMeta>, gapMs: Long, enabled: Boolean): List<GridItem> {
        if (!enabled || sorted.size < 3) return sorted.map { GridItem.Single(it) }
        val out = ArrayList<GridItem>()
        var run = ArrayList<Shot>()
        fun flush() {
            if (run.size >= 3) out += GridItem.Burst(run) else run.forEach { out += GridItem.Single(it) }
            run = ArrayList()
        }
        for (s in sorted) {
            val prev = run.lastOrNull()
            if (prev != null &&
                (prev.relDir != s.relDir || kotlin.math.abs(time(s, metas) - time(prev, metas)) > gapMs)
            ) flush()
            run += s
        }
        flush()
        return out
    }
}
