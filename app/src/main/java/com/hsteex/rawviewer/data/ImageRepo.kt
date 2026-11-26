package com.hsteex.rawviewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    /** Coda riservata al visualizzatore: non aspetta mai griglia o background. */
    private val ioViewer = Dispatchers.IO.limitedParallelism(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val maxMem = Runtime.getRuntime().maxMemory()
    private val gridCache = object : LruCache<String, Bitmap>((maxMem / 5).coerceAtMost(320L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val previewCache = object : LruCache<String, Bitmap>((maxMem / 5).coerceAtMost(256L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    /** Miniature EXIF (~160px): segnaposto immediato mentre arriva quella vera. */
    private val quickCache = object : LruCache<String, Bitmap>(16 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val ioQuick = Dispatchers.IO.limitedParallelism(2)
    private val infoCache = ConcurrentHashMap<String, FileInfo>()
    private val diskDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    /** Copie locali dei JPEG interi aperti nel visualizzatore (zoom senza toccare la SD). */
    private val fullDir = File(context.cacheDir, "full").apply { mkdirs() }

    init {
        scope.launch {
            // miniature della versione precedente (potevano venire dalla gain map HDR)
            diskDir.listFiles { f -> f.name.endsWith(".g.jpg") || f.name.endsWith(".g2.jpg") }?.forEach { it.delete() }
            trimDiskCache()
        }
    }

    // ------------------------------------------------------------------ priorità I/O
    //
    // La SD (via adattatore USB) ha poca banda e soffre le letture parallele.
    // Il lavoro in background (metadati, miniature su disco) cede il passo a ciò
    // che l'utente sta guardando: si ferma del tutto col visualizzatore aperto e
    // aspetta una breve pausa dopo ogni lettura "in primo piano" (miniature visibili).

    private val interactive = MutableStateFlow(false)
    private val fgCount = MutableStateFlow(0)
    @Volatile private var lastFg = 0L

    /** true mentre è aperto il visualizzatore (o il confronto): background in pausa. */
    fun setInteractive(on: Boolean) {
        if (!on) lastFg = SystemClock.uptimeMillis()
        if (interactive.value != on) DebugLog.d(LT, if (on) "background in pausa (visualizzatore)" else "background libero")
        interactive.value = on
    }

    private inline fun <T> foreground(block: () -> T): T {
        fgCount.update { it + 1 }
        try {
            return block()
        } finally {
            lastFg = SystemClock.uptimeMillis()
            fgCount.update { it - 1 }
        }
    }

    private suspend fun awaitBackgroundTurn() {
        val t0 = SystemClock.uptimeMillis()
        while (true) {
            combine(interactive, fgCount) { i, n -> !i && n == 0 }.first { it }
            val wait = lastFg + BG_COOLDOWN_MS - SystemClock.uptimeMillis()
            if (wait <= 0 && !interactive.value && fgCount.value == 0) {
                val waited = SystemClock.uptimeMillis() - t0
                if (waited > 1000) DebugLog.d(LT, "background ripreso dopo $waited ms di attesa")
                return
            }
            delay(wait.coerceAtLeast(50))
        }
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
    suspend fun indexShot(s: Shot): PhotoMeta? {
        awaitBackgroundTurn()
        return withContext(ioBg) { indexShotNow(s) }
    }

    private fun indexShotNow(s: Shot): PhotoMeta? {
        val pending = ArrayList<Pair<MediaFile, FileInfo>>()
        val primary = s.jpeg?.takeIf { it.kind == Kind.JPEG } ?: s.raw ?: s.jpeg
        if (primary != null && infoCache[primary.docId] == null) {
            val t0 = SystemClock.uptimeMillis()
            parseFile(primary)?.let { infoCache[primary.docId] = it; pending += primary to it }
            val ms = SystemClock.uptimeMillis() - t0
            if (ms > 80) DebugLog.d(LT, "metadati ${primary.name} lenti: $ms ms")
        }
        if (pending.isNotEmpty()) runCatching { db.putInfos(pending) }
        return metaOf(s)
    }

    private fun info(f: MediaFile): FileInfo? {
        infoCache[f.docId]?.let { return it }
        val parsed = DebugLog.time(LT, "metadati ${f.name} letti al volo") { parseFile(f) } ?: return null
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

    /** [meta]: dimensioni vere della foto, per togliere le bande nere delle anteprime incorporate. */
    private data class Src(
        val uri: Uri, val off: Long, val len: Int, val orientation: Int,
        val whole: Boolean = false, val meta: PhotoMeta? = null,
    )

    /**
     * Sorgenti candidate in ordine di convenienza (byte da leggere ↔ qualità).
     * Sequenza pigra: il RAW viene aperto e analizzato solo se il JPEG non basta.
     */
    private fun sources(s: Shot, allowWhole: Boolean): Sequence<Src> = sequence {
        val j = s.jpeg
        val jInfo = j?.takeIf { it.kind == Kind.JPEG }?.let { info(it) }
        if (j != null && jInfo != null && jInfo.prevLen > 0) {
            yield(Src(j.uri, jInfo.prevOff, jInfo.prevLen, jInfo.meta.orientation, meta = jInfo.meta))
        }
        val r = s.raw
        val rInfo = r?.let { info(it) }
        if (r != null && rInfo != null) {
            val o = rInfo.meta.orientation
            if (rInfo.prevLen > 0) yield(Src(r.uri, rInfo.prevOff, rInfo.prevLen, o, meta = rInfo.meta))
            if (rInfo.jpgLen > 0) yield(Src(r.uri, rInfo.jpgOff, rInfo.jpgLen, o, meta = rInfo.meta))
        }
        if (allowWhole && j != null) {
            yield(Src(j.uri, 0, 0, jInfo?.meta?.orientation ?: heifOrientation(j), whole = true))
        }
        if (j != null && jInfo != null && jInfo.thumbLen > 0) yield(Src(j.uri, jInfo.thumbOff, jInfo.thumbLen, jInfo.meta.orientation, meta = jInfo.meta))
        if (r != null && rInfo != null && rInfo.thumbLen > 0) yield(Src(r.uri, rInfo.thumbOff, rInfo.thumbLen, rInfo.meta.orientation, meta = rInfo.meta))
    }

    private fun heifOrientation(f: MediaFile): Int = runCatching {
        resolver.openFileDescriptor(f.uri, "r")?.use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrNull() ?: 1

    private fun decodeShot(s: Shot, targetLong: Int, allowWhole: Boolean = true): Bitmap? {
        for (src in sources(s, allowWhole)) {
            val bmp = try {
                if (src.whole) DebugLog.time(LT, "${s.baseName} file intero → ${targetLong}px") { decodeWhole(src.uri, targetLong) }
                else decodeRange(src, targetLong, s.baseName)
            } catch (e: Exception) {
                DebugLog.w(LT, "decodifica ${s.baseName} fallita", e)
                null
            }
            // le anteprime incorporate (Fuji: MPF 4:3, miniatura 160×120) hanno bande nere se la foto è 3:2 o 16:9
            if (bmp != null) {
                val cropped = if (src.whole) bmp else cropToAspect(bmp, src.meta?.width, src.meta?.height)
                return rotate(cropped, src.orientation)
            }
        }
        return null
    }

    private fun decodeRange(src: Src, targetLong: Int, name: String): Bitmap? {
        val t0 = SystemClock.uptimeMillis()
        val bytes = withReader(src.uri) { it.read(src.off, src.len) } ?: return null
        val tRead = SystemClock.uptimeMillis() - t0
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetLong)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val out = scaleDown(bmp, targetLong)
        DebugLog.d(
            LT, "$name → ${targetLong}px: letti ${bytes.size / 1024} KB in $tRead ms, decodifica " +
                "${SystemClock.uptimeMillis() - t0 - tRead} ms (${bounds.outWidth}×${bounds.outHeight}, campione ${opts.inSampleSize})",
        )
        return out
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

    fun peekQuick(s: Shot): Bitmap? = quickCache.get(s.key)

    /**
     * Miniatura EXIF incorporata: pochi KB da leggere, quindi quasi istantanea anche dalla SD.
     * Solo per scatti già indicizzati (non apre il file per analizzarlo) e solo se la vera non è già su disco.
     */
    suspend fun quickThumb(s: Shot): Bitmap? {
        quickCache.get(s.key)?.let { return it }
        return withContext(ioQuick) {
            if (gridFile(s).exists()) return@withContext null
            val j = s.jpeg?.takeIf { it.kind == Kind.JPEG }
            val src = j?.let { f -> infoCache[f.docId]?.let { f to it } }?.takeIf { it.second.thumbLen > 0 }
                ?: s.raw?.let { f -> infoCache[f.docId]?.let { f to it } }?.takeIf { it.second.thumbLen > 0 }
                ?: return@withContext null
            val (f, i) = src
            val bytes = runCatching { withReader(f.uri) { it.read(i.thumbOff, i.thumbLen) } }.getOrNull() ?: return@withContext null
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            bmp = cropToAspect(bmp, i.meta.width, i.meta.height)
            rotate(bmp, i.meta.orientation).also { quickCache.put(s.key, it) }
        }
    }

    /** Le miniature EXIF Fuji sono 160×120 con bande nere sui 3:2: si ritaglia al formato della foto. */
    private fun cropToAspect(b: Bitmap, w: Int?, h: Int?): Bitmap {
        if (w == null || h == null || w <= 0 || h <= 0) return b
        val want = max(w, h).toFloat() / minOf(w, h)
        val landscape = b.width >= b.height
        val have = if (landscape) b.width.toFloat() / b.height else b.height.toFloat() / b.width
        if (kotlin.math.abs(have - want) < 0.05f) return b
        val out = if (landscape) {
            if (want > have) { val nh = (b.width / want).toInt(); Bitmap.createBitmap(b, 0, (b.height - nh) / 2, b.width, nh) }
            else { val nw = (b.height * want).toInt(); Bitmap.createBitmap(b, (b.width - nw) / 2, 0, nw, b.height) }
        } else {
            if (want > have) { val nw = (b.height / want).toInt(); Bitmap.createBitmap(b, (b.width - nw) / 2, 0, nw, b.height) }
            else { val nh = (b.width * want).toInt(); Bitmap.createBitmap(b, 0, (b.height - nh) / 2, b.width, nh) }
        }
        return out
    }

    private fun gridFile(s: Shot) = File(diskDir, hash(s.display.cacheId + "|" + (s.raw?.cacheId ?: "")) + ".g3.jpg")

    suspend fun grid(s: Shot): Bitmap? = withContext(ioThumbs) { loadGrid(s, keepInMemory = true) }

    /** Generazione in background delle miniature (solo su disco). */
    suspend fun ensureGridOnDisk(s: Shot) {
        if (gridCache.get(s.key) != null || gridFile(s).exists()) return
        awaitBackgroundTurn()
        withContext(ioBg) {
            if (gridCache.get(s.key) == null && !gridFile(s).exists()) loadGrid(s, keepInMemory = false)
        }
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
        DebugLog.d(LT, "miniatura ${s.baseName} dalla SD (${if (keepInMemory) "a schermo" else "background"})")
        // keepInMemory = miniatura visibile a schermo: ha la precedenza sul background
        val bmp = (if (keepInMemory) foreground { decodeShot(s, GRID_PX) } else decodeShot(s, GRID_PX)) ?: return null
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
    suspend fun preview(s: Shot): Bitmap? {
        previewCache.get(s.key)?.let { return it }
        val tQueued = SystemClock.uptimeMillis()
        return withContext(ioViewer) {
            val queued = SystemClock.uptimeMillis() - tQueued
            if (queued > 50) DebugLog.d(LT, "anteprima ${s.baseName}: $queued ms in coda")
            foreground {
                previewCache.get(s.key) ?: decodeShot(s, PREVIEW_PX, allowWhole = false)
                    ?.also { previewCache.put(s.key, it) }
                    ?: loadGrid(s, true).also { DebugLog.w(LT, "anteprima ${s.baseName}: nessuna incorporata, uso la miniatura") }
            }
        }
    }

    /** Anteprime delle foto vicine, una alla volta (va chiamata dopo che la foto corrente è a schermo). */
    suspend fun prefetch(shots: List<Shot>) {
        for (s in shots) preview(s)
    }

    /** Sorgente a tile per lo zoom a piena risoluzione. */
    suspend fun viewerSource(s: Shot): SubSamplingImageSource? = withContext(ioViewer) {
        // l'anteprima la disegna il visualizzatore sotto la foto; telephoto non ne riceve una sua,
        // che a volte compariva per un fotogramma fuori posto (in basso a sinistra)
        val prev = preview(s)
        val j = s.jpeg
        when {
            j != null && j.kind == Kind.JPEG -> {
                val local = foreground { localCopy(j) }
                if (local != null) SubSamplingImageSource.file(local.toOkioPath())
                else SubSamplingImageSource.contentUri(j.uri)
            }
            j != null -> SubSamplingImageSource.contentUri(j.uri)
            s.raw != null -> {
                val file = foreground { embeddedJpegFile(s.raw) } ?: prev?.let { bitmapFile(s, it) }
                file?.let { SubSamplingImageSource.file(it.toOkioPath()) }
            }
            else -> null
        }
    }

    private val rawDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun canDevelopRaw(s: Shot): Boolean = RawDecoder.available && s.raw != null

    /** RAW sviluppato da LibRaw (neutro, senza simulazione pellicola), messo in cache su disco. */
    suspend fun rawSource(s: Shot): SubSamplingImageSource? = withContext(rawDispatcher) {
        val raw = s.raw ?: return@withContext null
        val f = File(diskDir, hash(raw.cacheId) + ".dev.jpg")
        if (!f.exists()) {
            val bmp = DebugLog.time(LT, "sviluppo RAW ${raw.name}") { RawDecoder.decode(context, raw.uri) } ?: return@withContext null
            val tmp = File(diskDir, f.name + ".tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            tmp.renameTo(f)
        }
        SubSamplingImageSource.file(f.toOkioPath())
    }

    fun hasDevelopedRaw(s: Shot): Boolean =
        s.raw?.let { File(diskDir, hash(it.cacheId) + ".dev.jpg").exists() } ?: false

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

    /**
     * Copia il file intero nella cache del telefono, a blocchi grandi e in un solo passaggio:
     * è il modo più rapido di leggere dalla SD, e poi lo zoom (decoder a regioni) lavora su
     * memoria locale con accesso casuale. Tornare su una foto già vista è istantaneo.
     */
    private suspend fun localCopy(f: MediaFile): File? {
        val out = File(fullDir, hash(f.cacheId) + ".jpg")
        if (out.exists() && out.length() == f.size) {
            out.setLastModified(System.currentTimeMillis())
            DebugLog.d(LT, "file intero ${f.name}: già in cache")
            return out
        }
        val tmp = File(fullDir, out.name + ".tmp")
        val t0 = SystemClock.uptimeMillis()
        try {
            val input = resolver.openInputStream(f.uri) ?: return null
            input.use { inp ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(out)) return null
            val ms = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1)
            DebugLog.d(LT, "file intero ${f.name}: ${f.size / 1048576} MB copiati in $ms ms (%.1f MB/s)".format(f.size / 1048576.0 / (ms / 1000.0)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            DebugLog.d(LT, "file intero ${f.name}: copia annullata dopo ${SystemClock.uptimeMillis() - t0} ms")
            tmp.delete()
            throw e
        } catch (e: Exception) {
            DebugLog.w(LT, "copia ${f.name} fallita", e)
            tmp.delete()
            return null
        }
        trimFullCache()
        return out
    }

    private fun trimFullCache() {
        val files = fullDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= MAX_FULL_FILES || total > MAX_FULL_DISK) f.delete()
        }
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

    fun diskCacheSize(): Long =
        (diskDir.listFiles()?.sumOf { it.length() } ?: 0L) + (fullDir.listFiles()?.sumOf { it.length() } ?: 0L)

    fun clearCaches() {
        gridCache.evictAll()
        quickCache.evictAll()
        previewCache.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
        fullDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "ImageRepo"
        private const val LT = "img"
        const val GRID_PX = 640
        const val PREVIEW_PX = 2048
        private const val MAX_DISK = 800L shl 20
        /** Pausa minima del background dopo l'ultima lettura in primo piano. */
        private const val BG_COOLDOWN_MS = 700L
        private const val MAX_FULL_FILES = 40
        private const val MAX_FULL_DISK = 700L shl 20
    }
}
