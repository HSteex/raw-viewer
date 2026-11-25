package com.hsteex.rawviewer.data

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToLong

/** Letture posizionali: leggiamo solo i byte che servono, mai il file intero. */
class RandomReader(private val channel: FileChannel) {
    val size: Long = channel.size()

    fun read(pos: Long, len: Int): ByteArray {
        if (pos < 0 || len <= 0 || pos >= size) return ByteArray(0)
        val n = min(len.toLong(), size - pos).toInt()
        val buf = ByteBuffer.allocate(n)
        var p = pos
        while (buf.hasRemaining()) {
            val r = channel.read(buf, p)
            if (r <= 0) break
            p += r
        }
        return if (buf.position() == n) buf.array() else buf.array().copyOf(buf.position())
    }
}

/**
 * Parser minimale di JPEG/EXIF/MPF e RAF Fujifilm.
 * Estrae: metadati di scatto, miniatura EXIF (160px), anteprima MPF (~1600px)
 * e la posizione del JPEG incorporato nei RAF.
 */
object MetaParser {

    private const val MAX_PREVIEW = 12 * 1024 * 1024

    fun parseJpeg(r: RandomReader): FileInfo? = parseJpegAt(r, 0)

    fun parseRaf(r: RandomReader): FileInfo? {
        val h = r.read(0, 100)
        if (h.size < 92) return null
        if (String(h, 0, 15, Charsets.US_ASCII) != "FUJIFILMCCD-RAW") return null
        val off = be32(h, 84)
        val len = be32(h, 88)
        if (off <= 0 || len <= 0 || off + len > r.size) return null
        val inner = parseJpegAt(r, off) ?: FileInfo(PhotoMeta.EMPTY)
        return inner.copy(jpgOff = off, jpgLen = len.toInt())
    }

    fun parseJpegAt(r: RandomReader, base: Long): FileInfo? {
        val soi = r.read(base, 2)
        if (soi.size < 2 || u8(soi, 0) != 0xFF || u8(soi, 1) != 0xD8) return null
        var pos = base + 2
        var meta: PhotoMeta? = null
        var thumbOff = 0L
        var thumbLen = 0
        var prevOff = 0L
        var prevLen = 0
        var guard = 0
        while (guard++ < 64) {
            val h = r.read(pos, 4)
            if (h.size < 4 || u8(h, 0) != 0xFF) break
            val marker = u8(h, 1)
            if (marker == 0xFF) { pos += 1; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }
            if (marker == 0xDA || marker == 0xD9) break
            val len = (u8(h, 2) shl 8) or u8(h, 3)
            if (len < 2) break
            val dataStart = pos + 4
            val dataLen = len - 2
            if (marker == 0xE1 && meta == null) {
                val d = r.read(dataStart, dataLen)
                if (d.size >= 14 && d[0] == 'E'.b && d[1] == 'x'.b && d[2] == 'i'.b && d[3] == 'f'.b) {
                    val res = parseExif(d, 6)
                    if (res != null) {
                        meta = res.meta
                        if (res.thumbOff > 0 && res.thumbLen > 0) {
                            thumbOff = dataStart + 6 + res.thumbOff
                            thumbLen = res.thumbLen
                        }
                    }
                }
            } else if (marker == 0xE2 && prevLen == 0) {
                val head = r.read(dataStart, 4)
                if (head.size == 4 && head[0] == 'M'.b && head[1] == 'P'.b && head[2] == 'F'.b && head[3] == 0.toByte()) {
                    val d = r.read(dataStart, dataLen)
                    val best = parseMpf(d, 4)
                    if (best != null) {
                        prevOff = dataStart + 4 + best.first
                        prevLen = best.second
                    }
                }
            }
            pos = dataStart + dataLen
        }
        return FileInfo(meta ?: PhotoMeta.EMPTY, thumbOff, thumbLen, prevOff, prevLen)
    }

    // ---------------- EXIF ----------------

    private class ExifResult(val meta: PhotoMeta, val thumbOff: Long, val thumbLen: Int)

    private fun parseExif(d: ByteArray, start: Int): ExifResult? {
        val t = Tiff(d, start)
        if (t.u16(2) != 42) return null
        val ifd0 = t.u32(4).toInt()
        var make: String? = null
        var model: String? = null
        var orientation = 1
        var exifPtr = 0
        for (e in t.entries(ifd0)) {
            when (e.tag) {
                0x010F -> make = t.ascii(e)
                0x0110 -> model = t.ascii(e)
                0x0112 -> orientation = t.int(e).toInt().takeIf { it in 1..8 } ?: 1
                0x8769 -> exifPtr = t.int(e).toInt()
            }
        }
        var thumbOff = 0L
        var thumbLen = 0
        val ifd1 = t.nextIfd(ifd0)
        if (ifd1 > 0) {
            for (e in t.entries(ifd1)) {
                when (e.tag) {
                    0x0201 -> thumbOff = t.int(e)
                    0x0202 -> thumbLen = t.int(e).toInt()
                }
            }
        }

        var exposure: String? = null
        var fNumber: Double? = null
        var iso: Int? = null
        var focal: Double? = null
        var bias: Double? = null
        var lens: String? = null
        var dto: String? = null
        var subSec: String? = null
        var w: Int? = null
        var h: Int? = null
        var film: String? = null
        var focus: Pair<Int, Int>? = null
        if (exifPtr > 0) {
            for (e in t.entries(exifPtr)) {
                when (e.tag) {
                    0x829A -> exposure = t.rational(e)?.let { formatExposure(it) }
                    0x829D -> fNumber = t.rational(e)
                    0x8827 -> iso = t.int(e).toInt().takeIf { it > 0 }
                    0x9003 -> dto = t.ascii(e)
                    0x9291 -> subSec = t.ascii(e)
                    0x920A -> focal = t.rational(e)
                    0x9204 -> bias = t.rational(e)
                    0xA434 -> lens = t.ascii(e)
                    0xA002 -> w = t.int(e).toInt().takeIf { it > 0 }
                    0xA003 -> h = t.int(e).toInt().takeIf { it > 0 }
                    0x927C -> parseFuji(t, e)?.let { film = it.film; focus = it.focus }
                }
            }
        }
        val meta = PhotoMeta(
            dateTaken = parseDate(dto, subSec),
            make = make, model = model, lens = lens,
            exposure = exposure, fNumber = fNumber, iso = iso, focal = focal, bias = bias,
            width = w, height = h, orientation = orientation, film = film,
            focusX = focus?.first, focusY = focus?.second,
        )
        return ExifResult(meta, thumbOff, thumbLen)
    }

    private class FujiInfo(val film: String?, val focus: Pair<Int, Int>?)

    /** MakerNote Fujifilm: simulazione pellicola (0x1401), B/N-Acros (0x1003), punto AF (0x1031 FocusPixel). */
    private fun parseFuji(t: Tiff, e: Entry): FujiInfo? {
        val mn = t.dataOffset(e)
        if (!t.valid(mn, 12)) return null
        val sig = String(t.b, t.start + mn, 8, Charsets.US_ASCII)
        if (sig != "FUJIFILM") return null
        val mt = Tiff(t.b, t.start + mn, le = true)
        val ifd = mt.u32(8).toInt()
        var filmMode: Int? = null
        var saturation: Int? = null
        var focus: Pair<Int, Int>? = null
        for (x in mt.entries(ifd)) {
            when (x.tag) {
                0x1401 -> filmMode = mt.int(x).toInt()
                0x1003 -> saturation = mt.int(x).toInt()
                0x1031 -> if (x.type == 3 && x.count >= 2) {
                    val o = mt.dataOffset(x)
                    if (mt.valid(o, 4)) {
                        val fx = mt.u16(o)
                        val fy = mt.u16(o + 2)
                        if (fx > 0 && fy > 0) focus = fx to fy
                    }
                }
            }
        }
        return FujiInfo(filmName(filmMode, saturation), focus)
    }

    private fun filmName(filmMode: Int?, saturation: Int?): String? {
        val bw = when (saturation) {
            0x300 -> "Monocromatico"
            0x301 -> "Monocromatico + R"
            0x302 -> "Monocromatico + Ye"
            0x303 -> "Monocromatico + G"
            0x310 -> "Seppia"
            0x500 -> "Acros"
            0x501 -> "Acros + R"
            0x502 -> "Acros + Ye"
            0x503 -> "Acros + G"
            else -> null
        }
        if (bw != null) return bw
        return when (filmMode) {
            null -> null
            0x000 -> "Provia / Standard"
            0x100, 0x110, 0x120, 0x130 -> "Provia (F1)"
            0x200 -> "Velvia (F2)"
            0x300 -> "Astia / Soft"
            0x400 -> "Velvia / Vivid"
            0x500 -> "Pro Neg. Std"
            0x501 -> "Pro Neg. Hi"
            0x600 -> "Classic Chrome"
            0x700 -> "Eterna"
            0x800 -> "Classic Negative"
            0x900 -> "Eterna Bleach Bypass"
            0xA00 -> "Nostalgic Neg."
            0xB00 -> "Reala Ace"
            else -> "Simulazione 0x" + filmMode.toString(16)
        }
    }

    // ---------------- MPF ----------------

    /**
     * Ritorna (offset relativo all'header MPF, lunghezza) dell'anteprima secondaria più grande.
     * Solo le voci marcate come "Large Thumbnail" (tipo 0x010001 / 0x010002), come quelle Fuji:
     * negli Ultra HDR (Pixel e altri) l'immagine secondaria è la gain map in scala di grigi, non un'anteprima.
     */
    private fun parseMpf(d: ByteArray, start: Int): Pair<Long, Int>? {
        val t = Tiff(d, start)
        if (t.u16(2) != 42) return null
        val ifd = t.u32(4).toInt()
        var best: Pair<Long, Int>? = null
        for (e in t.entries(ifd)) {
            if (e.tag != 0xB002) continue
            val o = t.dataOffset(e)
            val n = (e.count / 16).toInt().coerceAtMost(16)
            for (i in 0 until n) {
                val p = o + i * 16
                if (!t.valid(p, 16)) break
                val type = t.u32(p).toInt() and 0xFFFFFF
                val size = t.u32(p + 4)
                val off = t.u32(p + 8)
                if ((type == 0x010001 || type == 0x010002) && off > 0 && size in 1..MAX_PREVIEW) {
                    if (best == null || size > best.second) best = off to size.toInt()
                }
            }
        }
        return best
    }

    // ---------------- helpers ----------------

    private val Char.b: Byte get() = code.toByte()

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF

    private fun be32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or
            (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun parseDate(s: String?, subSec: String?): Long? {
        if (s == null || s.length < 19) return null
        return try {
            val base = LocalDateTime.parse(s.substring(0, 19), dateFmt)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val ms = subSec?.filter { it.isDigit() }?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
            base + ms
        } catch (e: Exception) {
            null
        }
    }

    fun formatExposure(v: Double): String = when {
        v <= 0 -> "?"
        v >= 1.0 -> if (v == v.roundToLong().toDouble()) "${v.roundToLong()}s" else "%.1fs".format(v)
        else -> "1/${(1.0 / v).roundToLong()}"
    }
}

data class Entry(val tag: Int, val type: Int, val count: Long, val pos: Int)

/** Vista TIFF su un ByteArray: offset relativi all'inizio dell'header TIFF. */
class Tiff(val b: ByteArray, val start: Int, le: Boolean? = null) {
    val le: Boolean = le ?: (start in b.indices && b[start] == 'I'.code.toByte())

    fun valid(o: Int, n: Int = 1): Boolean = o >= 0 && start.toLong() + o + n <= b.size

    private fun u8(o: Int): Int = if (valid(o)) b[start + o].toInt() and 0xFF else 0

    fun u16(o: Int): Int = if (le) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)

    fun u32(o: Int): Long = if (le) {
        u8(o).toLong() or (u8(o + 1).toLong() shl 8) or (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24)
    } else {
        (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
    }

    fun entries(ifd: Int): List<Entry> {
        if (ifd <= 0 || !valid(ifd, 2)) return emptyList()
        val n = u16(ifd)
        if (n == 0 || n > 1000) return emptyList()
        val out = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val p = ifd + 2 + i * 12
            if (!valid(p, 12)) break
            out += Entry(u16(p), u16(p + 2), u32(p + 4), p)
        }
        return out
    }

    fun nextIfd(ifd: Int): Int {
        if (!valid(ifd, 2)) return 0
        val n = u16(ifd)
        val p = ifd + 2 + n * 12
        return if (valid(p, 4)) u32(p).toInt() else 0
    }

    private fun typeSize(t: Int) = when (t) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    fun dataOffset(e: Entry): Int {
        val sz = typeSize(e.type).toLong() * e.count
        return if (sz <= 4) e.pos + 8 else u32(e.pos + 8).toInt()
    }

    fun int(e: Entry): Long {
        val o = dataOffset(e)
        return when (e.type) {
            3 -> u16(o).toLong()
            8 -> u16(o).toShort().toLong()
            4 -> u32(o)
            9 -> u32(o).toInt().toLong()
            1, 7 -> u8(o).toLong()
            else -> 0
        }
    }

    fun rational(e: Entry): Double? {
        val o = dataOffset(e)
        if (!valid(o, 8)) return null
        val signed = e.type == 10
        val num = if (signed) u32(o).toInt().toDouble() else u32(o).toDouble()
        val den = if (signed) u32(o + 4).toInt().toDouble() else u32(o + 4).toDouble()
        if (e.type != 5 && e.type != 10) return int(e).toDouble()
        return if (den == 0.0) null else num / den
    }

    fun ascii(e: Entry): String? {
        val o = dataOffset(e)
        val n = e.count.coerceAtMost(512).toInt()
        if (n <= 0 || !valid(o, n)) return null
        return String(b, start + o, n, Charsets.US_ASCII)
            .trimEnd('\u0000').trim().ifEmpty { null }
    }
}
