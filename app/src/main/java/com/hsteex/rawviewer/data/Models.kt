package com.hsteex.rawviewer.data

import android.net.Uri

enum class Kind { JPEG, HEIF, RAW }

data class MediaFile(
    val uri: Uri,
    val docId: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val kind: Kind,
    val relDir: String,
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val baseName: String get() = name.substringBeforeLast('.')
    val cacheId: String get() = "$docId|$size|$modified"
}

/** Uno "scatto": JPEG (o HEIF) e RAW con lo stesso nome nella stessa cartella. */
data class Shot(
    val key: String,
    val relDir: String,
    val baseName: String,
    val jpeg: MediaFile?,
    val raw: MediaFile?,
    val modified: Long,
) {
    val display: MediaFile get() = jpeg ?: raw!!
    val badge: String
        get() {
            val j = jpeg?.let { if (it.kind == Kind.HEIF) "HIF" else "JPG" }
            val r = raw?.ext?.uppercase()
            return when {
                j != null && r != null -> "$r+$j"
                r != null -> r
                else -> j ?: ""
            }
        }
    val totalSize: Long get() = (jpeg?.size ?: 0) + (raw?.size ?: 0)
}

enum class Flag(val code: Int) {
    NONE(0), PICK(1), REJECT(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** Voto di uno scatto. [downloaded]: già copiato sul telefono (non è un voto, ma vive nella stessa riga). */
data class Rating(val stars: Int = 0, val flag: Flag = Flag.NONE, val downloaded: Boolean = false) {
    /** Nessun voto dato (stelle o ✓/✗). */
    val isUnrated: Boolean get() = stars == 0 && flag == Flag.NONE
    /** Niente da salvare nel DB. */
    val isEmpty: Boolean get() = isUnrated && !downloaded
}

data class PhotoMeta(
    val dateTaken: Long? = null,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val exposure: String? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focal: Double? = null,
    val bias: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 1,
    val film: String? = null,
    /** Punto di messa a fuoco (MakerNote Fuji, pixel dell'immagine non ruotata). */
    val focusX: Int? = null,
    val focusY: Int? = null,
) {
    companion object {
        val EMPTY = PhotoMeta()
    }
}

/** Metadati + posizione delle anteprime incorporate dentro un file. */
data class FileInfo(
    val meta: PhotoMeta,
    val thumbOff: Long = 0,
    val thumbLen: Int = 0,
    val prevOff: Long = 0,
    val prevLen: Int = 0,
    /** JPEG incorporato (RAF). 0 = il file stesso è un JPEG. */
    val jpgOff: Long = 0,
    val jpgLen: Int = 0,
)

enum class ImportFormat(val label: String) {
    JPEG("Solo JPEG"), RAW("Solo RAW"), BOTH("JPEG + RAW")
}

data class RootEntry(val uri: Uri, val name: String, val lastOpened: Long)
