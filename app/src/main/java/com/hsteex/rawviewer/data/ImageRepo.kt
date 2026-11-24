package com.hsteex.rawviewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Tutto ciò che riguarda immagini: metadati, miniature (memoria + disco),
 * anteprime per il visualizzatore. Non decodifica mai un RAW: usa le anteprime
 * JPEG incorporate, leggendo solo i byte necessari.
 */
class ImageRepo(private val context: Context, private val db: Db) {

    private val resolver = context.contentResolver
    private val ioThumbs = Dispatchers.IO.limitedParallelism(4)
    private val ioBg = Dispatchers.IO.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val maxMem = Runtime.getRuntime().maxMemory()
    private val gridCache = object : LruCache<String, Bitmap>((maxMem / 5).coerceAtMost(320L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val previewCache = object : LruCache<String, Bitmap>((maxMem / 5).coerceAtMost(256L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val infoCache = ConcurrentHashMap<String, FileInfo>()
    private val diskDir = File(context.cacheDir, "thumbs").apply { mkdirs() }

    init {
        scope.launch { trimDiskCache() }
    }

    // ------------------------------------------------------------------ info

    /** Carica dal DB la cache dei metadati; ritorna i meta degli scatti già noti. */
    fun preloadInfos(shots: List<Shot>): Map<String, PhotoMeta> {
        val all = db.loadAllInfo()
        val out = HashMap<String, PhotoMeta>()
        for (s in shots) {
            for (f in listOfNotNull(s.jpeg, s.raw)) {
                val c = all[f.docId] ?: continue
                if (c.size == f.size && c.mtime == f.modified) infoCache[f.docId] = c.info
            }
            metaOf(s)?.let { out[s.key] = it }
        }
        return out
    }

    private fun metaOf(s: Shot): PhotoMeta? {
        val j = s.jpeg?.let { infoCache[it.docId] }?.meta
        if (j != null && j.dateTaken != null) return j
        val r = s.raw?.let { infoCache[it.docId] }?.meta
        return if (r != null && r.dateTaken != null) r else j ?: r
    }

    fun hasMeta(s: Shot): Boolean = metaOf(s) != null

    /** Legge (se serve) i metadati di uno scatto. Usato dall'indicizzazione in background. */
    suspend fun indexShot(s: Shot): PhotoMeta? = withContext(ioBg) {
        val pending = ArrayList<Pair<MediaFile, FileInfo>>()
        val primary = s.jpeg?.takeIf { it.kind == Kind.JPEG } ?: s.raw ?: s.jpeg
        if (primary != null && infoCache[primary.docId] == null) {
            parseFile(primary)?.let { infoCache[primary.docId] = it; pending += primary to it }
        }
        if (pending.isNotEmpty()) runCatching { db.putInfos(pending) }
        metaOf(s)
    }

    private fun info(f: MediaFile): FileInfo? {
        infoCache[f.docId]?.let { return it }
        val parsed = parseFile(f) ?: return null
        infoCache[f.docId] = parsed
        runCatching { db.putInfos(listOf(f to parsed)) }
        return parsed
    }

    private fun parseFile(f: MediaFile): FileInfo? = try {
        when {
            f.kind == Kind.JPEG -> withReader(f.uri) { MetaParser.parseJpeg(it) }
            f.kind == Kind.RAW && f.ext == "raf" -> withReader(f.uri) { MetaParser.parseRaf(it) }
                ?: parseWithExifInterface(f)
            else -> parseWithExifInterface(f)
        }
    } catch (e: Exception) {
        Log.w(TAG, "parse ${f.name}", e)
        null
    }

    private fun parseWithExifInterface(f: MediaFile): FileInfo? {
        return resolver.openFileDescriptor(f.uri, "r")?.use { pfd ->
            val ei = ExifInterface(pfd.fileDescriptor)
            val range = runCatching { if (ei.isThumbnailCompressed) ei.thumbnailRange else null }.getOrNull()
            val dto = ei.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val meta = PhotoMeta(
                dateTaken = MetaParser.parseDate(dto, ei.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)),
                make = ei.getAttribute(ExifInterface.TAG_MAKE),
                model = ei.getAttribute(ExifInterface.TAG_MODEL),
                lens = ei.getAttribute(ExifInterface.TAG_LENS_MODEL),
                exposure = ei.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    .takeIf { it > 0 }?.let { MetaParser.formatExposure(it) },
                fNumber = ei.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
                iso = ei.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                focal = ei.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 },
                bias = ei.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0),
                width = ei.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 },
                height = ei.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 },
                orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1),
            )
            if (dto == null && meta.model == null && range == null) return@use FileInfo(PhotoMeta.EMPTY)
            FileInfo(
                meta = meta,
                thumbOff = range?.getOrNull(0) ?: 0L,
                thumbLen = range?.getOrNull(1)?.toInt() ?: 0,
            )
        }
    }

    fun metaFor(s: Shot): PhotoMeta? = metaOf(s)

    // ------------------------------------------------------------------ sorgenti

    private data class Src(val uri: Uri, val off: Long, val len: Int, val orientation: Int, val whole: Boolean = false)

    /** Sorgenti candidate in ordine di convenienza (byte da leggere ↔ qualità). */
    private fun sources(s: Shot, allowWhole: Boolean): List<Src> {
        val list = ArrayList<Src>(4)
        val j = s.jpeg
        val jInfo = j?.takeIf { it.kind == Kind.JPEG }?.let { info(it) }
        if (j != null && jInfo != null && jInfo.prevLen > 0) {
            list += Src(j.uri, jInfo.prevOff, jInfo.prevLen, jInfo.meta.orientation)
        }
        val r = s.raw
        val rInfo = r?.let { info(it) }
        if (r != null && rInfo != null) {
            val o = rInfo.meta.orientation
            if (rInfo.prevLen > 0) list += Src(r.uri, rInfo.prevOff, rInfo.prevLen, o)
            if (rInfo.jpgLen > 0) list += Src(r.uri, rInfo.jpgOff, rInfo.jpgLen, o)
        }
        if (allowWhole && j != null) {
            list += Src(j.uri, 0, 0, jInfo?.meta?.orientation ?: heifOrientation(j), whole = true)
        }
        if (jInfo != null && jInfo.thumbLen > 0) list += Src(j.uri, jInfo.thumbOff, jInfo.thumbLen, jInfo.meta.orientation)
        if (rInfo != null && rInfo.thumbLen > 0) list += Src(r.uri, rInfo.thumbOff, rInfo.thumbLen, rInfo.meta.orientation)
        return list
    }

    private fun heifOrientation(f: MediaFile): Int = runCatching {
        resolver.openFileDescriptor(f.uri, "r")?.use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrNull() ?: 1

    private fun decodeShot(s: Shot, targetLong: Int, allowWhole: Boolean = true): Bitmap? {
        for (src in sources(s, allowWhole)) {
            val bmp = try {
                if (src.whole) decodeWhole(src.uri, targetLong) else decodeRange(src, targetLong)
            } catch (e: Exception) {
                Log.w(TAG, "decode ${s.baseName}", e)
                null
            }
            if (bmp != null) return rotate(bmp, src.orientation)
        }
        return null
    }

    private fun decodeRange(src: Src, targetLong: Int): Bitmap? {
        val bytes = withReader(src.uri) { it.read(src.off, src.len) } ?: return null
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetLong)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return scaleDown(bmp, targetLong)
    }

    private fun decodeWhole(uri: Uri, targetLong: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openFileDescriptor(uri, "r")?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetLong)
        }
        val bmp = resolver.openFileDescriptor(uri, "r")?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, opts)
        } ?: return null
        return scaleDown(bmp, targetLong)
    }

    private fun sampleSize(w: Int, h: Int, targetLong: Int): Int {
        var s = 1
        val longSide = max(w, h)
        while (longSide / (s * 2) >= targetLong) s *= 2
        return s
    }

    private fun scaleDown(b: Bitmap, targetLong: Int): Bitmap {
        val longSide = max(b.width, b.height)
        if (longSide <= targetLong * 5 / 4) return b
        val f = targetLong.toFloat() / longSide
        val out = Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
        if (out !== b) b.recycle()
        return out
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
            else -> return b
        }
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (out !== b) b.recycle()
        return out
    }

    // ------------------------------------------------------------------ griglia

    fun peekGrid(s: Shot): Bitmap? = gridCache.get(s.key)

    private fun gridFile(s: Shot) = File(diskDir, hash(s.display.cacheId + "|" + (s.raw?.cacheId ?: "")) + ".g.jpg")

    suspend fun grid(s: Shot): Bitmap? = withContext(ioThumbs) { loadGrid(s, keepInMemory = true) }

    /** Generazione in background delle miniature (solo su disco). */
    suspend fun ensureGridOnDisk(s: Shot) = withContext(ioBg) {
        if (gridCache.get(s.key) == null && !gridFile(s).exists()) loadGrid(s, keepInMemory = false)
    }

    private fun loadGrid(s: Shot, keepInMemory: Boolean): Bitmap? {
        gridCache.get(s.key)?.let { return it }
        val f = gridFile(s)
        if (f.exists()) {
            BitmapFactory.decodeFile(f.path)?.let {
                if (keepInMemory) gridCache.put(s.key, it)
                return it
            }
        }
        val bmp = decodeShot(s, GRID_PX) ?: return null
        try {
            val tmp = File(diskDir, f.name + ".tmp" + Thread.currentThread().id)
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "save thumb", e)
        }
        if (keepInMemory) gridCache.put(s.key, bmp)
        return bmp
    }

    // ------------------------------------------------------------------ visualizzatore

    fun peekPreview(s: Shot): Bitmap? = previewCache.get(s.key)

    /** Anteprima grande (MPF ~1600px o JPEG incorporato) per mostrare subito la foto. */
    suspend fun preview(s: Shot): Bitmap? = withContext(ioThumbs) {
        previewCache.get(s.key) ?: decodeShot(s, PREVIEW_PX, allowWhole = false)
            ?.also { previewCache.put(s.key, it) }
            ?: loadGrid(s, true)
    }

    fun prefetch(shots: List<Shot>) {
        for (s in shots) {
            if (previewCache.get(s.key) != null) continue
            scope.launch(ioBg) {
                if (previewCache.get(s.key) == null) {
                    decodeShot(s, PREVIEW_PX, allowWhole = false)?.let { previewCache.put(s.key, it) }
                }
            }
        }
    }

    /** Sorgente a tile per lo zoom a piena risoluzione. */
    suspend fun viewerSource(s: Shot): SubSamplingImageSource? = withContext(Dispatchers.IO) {
        val prev = preview(s)
        val prevImg = prev?.asImageBitmap()
        val j = s.jpeg
        when {
            j != null -> SubSamplingImageSource.contentUri(j.uri, prevImg)
            s.raw != null -> {
                val file = embeddedJpegFile(s.raw) ?: prev?.let { bitmapFile(s, it) }
                file?.let { SubSamplingImageSource.file(it.toOkioPath(), prevImg) }
            }
            else -> null
        }
    }

    /** Estrae su disco il JPEG incorporato nel RAF (per i RAW senza JPEG). */
    private fun embeddedJpegFile(raw: MediaFile): File? {
        val i = info(raw) ?: return null
        if (i.jpgLen <= 0) return null
        val f = File(diskDir, hash(raw.cacheId) + ".emb.jpg")
        if (f.exists() && f.length() == i.jpgLen.toLong()) return f
        val bytes = withReader(raw.uri) { it.read(i.jpgOff, i.jpgLen) } ?: return null
        val tmp = File(diskDir, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        tmp.renameTo(f)
        return f
    }

    private fun bitmapFile(s: Shot, b: Bitmap): File {
        val f = File(diskDir, hash(s.display.cacheId) + ".prev.jpg")
        if (!f.exists()) FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f
    }

    // ------------------------------------------------------------------ util

    private inline fun <T> withReader(uri: Uri, block: (RandomReader) -> T): T? {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            block(RandomReader(stream.channel))
        }
    }

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun trimDiskCache() {
        val files = diskDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total < MAX_DISK) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total < MAX_DISK * 2 / 3) break
            total -= f.length()
            f.delete()
        }
    }

    fun clearCaches() {
        gridCache.evictAll()
        previewCache.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "ImageRepo"
        const val GRID_PX = 640
        const val PREVIEW_PX = 2048
        private const val MAX_DISK = 800L shl 20
    }
}
