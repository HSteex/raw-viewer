package com.hsteex.rawviewer.data

import android.graphics.Bitmap
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

        /** Maschera trasparente (metà risoluzione): rosso dove è bruciato, blu dove è chiuso. */
        fun clippingMask(b: Bitmap): Bitmap {
            val step = if (max(b.width, b.height) > 1200) 2 else 1
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
