package com.hsteex.rawviewer.data

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Istogramma e controllo bruciature, calcolati sull'anteprima già in memoria (~1600px):
 * nessuna lettura dalla SD, qualche decina di ms su un thread di calcolo.
 */
class ImageStats(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luma: IntArray,
    /** Frazione di pixel con almeno un canale saturo (luci bruciate). */
    val clippedHigh: Float,
    /** Frazione di pixel con tutti i canali quasi neri (ombre chiuse). */
    val clippedLow: Float,
) {
    companion object {
        const val HIGH = 253
        const val LOW = 2
        const val MASK_HIGH = 0xFFFF2D55.toInt()
        const val MASK_LOW = 0xFF3D8BFF.toInt()

        fun analyze(b: Bitmap): ImageStats {
            val w = b.width
            val h = b.height
            // al massimo ~400k pixel campionati: l'istogramma non cambia, il tempo sì
            val step = max(1, kotlin.math.sqrt(w.toDouble() * h / 400_000.0).toInt())
            val r = IntArray(256)
            val g = IntArray(256)
            val bl = IntArray(256)
            val l = IntArray(256)
            var hi = 0
            var lo = 0
            var n = 0
            val row = IntArray(w)
            var y = 0
            while (y < h) {
                b.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val c = row[x]
                    val cr = (c shr 16) and 0xFF
                    val cg = (c shr 8) and 0xFF
                    val cb = c and 0xFF
                    r[cr]++; g[cg]++; bl[cb]++
                    l[(cr * 54 + cg * 183 + cb * 19) shr 8]++
                    if (max(cr, max(cg, cb)) >= HIGH) hi++
                    else if (max(cr, max(cg, cb)) <= LOW) lo++
                    n++
                    x += step
                }
                y += step
            }
            val tot = max(1, n).toFloat()
            return ImageStats(r, g, bl, l, hi / tot, lo / tot)
        }

        const val MASK_FOCUS = 0xFF3DFF5A.toInt()

        /**
         * Focus peaking: evidenzia i bordi più netti (laplaciano della luminanza), cioè le zone a fuoco.
         * Soglia adattiva: il ~3% di pixel più contrastati, ma mai sotto un minimo assoluto, così una foto
         * tutta mossa resta quasi senza segni. Calcolato sull'anteprima: indica *dove* è il fuoco,
         * per giudicare la nitidezza vera serve lo zoom al 100%.
         */
        fun focusMask(b: Bitmap): Bitmap {
            val step = 1 // piena risoluzione dell'anteprima: niente quadrettoni
            val w = b.width / step
            val h = b.height / step
            val lum = IntArray(w * h)
            val row = IntArray(b.width)
            for (y in 0 until h) {
                b.getPixels(row, 0, b.width, 0, min(y * step, b.height - 1), b.width, 1)
                for (x in 0 until w) {
                    val c = row[x * step]
                    lum[y * w + x] = (((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8
                }
            }
            val lap = IntArray(w * h)
            val hist = IntArray(1021)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val v = abs(4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w])
                    lap[i] = v
                    hist[v]++
                }
            }
            val total = (w - 2).coerceAtLeast(1) * (h - 2).coerceAtLeast(1)
            var acc = 0
            var thr = 1020
            while (thr > 0) {
                acc += hist[thr]
                if (acc >= total * 0.03) break
                thr--
            }
            thr = max(thr, 48)
            val out = IntArray(w * h)
            for (i in lap.indices) if (lap[i] >= thr) out[i] = MASK_FOCUS
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }

        /** Maschera trasparente (metà risoluzione): rosso dove è bruciato, blu dove è chiuso. */
        fun clippingMask(b: Bitmap): Bitmap {
            val step = 1 // piena risoluzione dell'anteprima: niente quadrettoni
            val mw = b.width / step
            val mh = b.height / step
            val out = IntArray(mw * mh)
            val row = IntArray(b.width)
            for (my in 0 until mh) {
                b.getPixels(row, 0, b.width, 0, min(my * step, b.height - 1), b.width, 1)
                val base = my * mw
                for (mx in 0 until mw) {
                    val c = row[mx * step]
                    val m = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF))
                    out[base + mx] = when {
                        m >= HIGH -> MASK_HIGH
                        m <= LOW -> MASK_LOW
                        else -> 0
                    }
                }
            }
            return Bitmap.createBitmap(out, mw, mh, Bitmap.Config.ARGB_8888)
        }
    }
}
