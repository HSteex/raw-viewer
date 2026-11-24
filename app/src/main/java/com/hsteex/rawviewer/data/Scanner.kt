package com.hsteex.rawviewer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scansione veloce via Storage Access Framework: UNA query per cartella con
 * tutte le colonne necessarie (niente DocumentFile, che fa una IPC per ogni proprietà).
 */
object Scanner {

    val RAW_EXT = setOf(
        "raf", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "pef",
        "srw", "dng", "raw", "rwl", "3fr", "iiq", "x3f", "erf", "mrw", "kdc", "dcr",
    )
    private val JPEG_EXT = setOf("jpg", "jpeg")
    private val HEIF_EXT = setOf("hif", "heif", "heic")

    private val PROJ = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    private val io = Dispatchers.IO.limitedParallelism(6)

    fun classify(name: String): Kind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in JPEG_EXT -> Kind.JPEG
            in HEIF_EXT -> Kind.HEIF
            in RAW_EXT -> Kind.RAW
            else -> null
        }
    }

    suspend fun scan(context: Context, tree: Uri, counter: AtomicInteger): List<MediaFile> =
        withContext(io) {
            val resolver = context.contentResolver
            val out = ConcurrentLinkedQueue<MediaFile>()
            val rootId = DocumentsContract.getTreeDocumentId(tree)

            suspend fun scanDir(docId: String, rel: String) {
                coroutineScope {
                    val subdirs = ArrayList<Pair<String, String>>()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                    resolver.query(children, PROJ, null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue
                            val name = c.getString(1) ?: continue
                            val mime = c.getString(2)
                            if (mime == Document.MIME_TYPE_DIR) {
                                if (!name.startsWith(".")) {
                                    subdirs += id to (if (rel.isEmpty()) name else "$rel/$name")
                                }
                                continue
                            }
                            if (name.startsWith("._")) continue // file spazzatura di macOS
                            val kind = classify(name) ?: continue
                            val size = if (c.isNull(3)) 0L else c.getLong(3)
                            val mod = if (c.isNull(4)) 0L else c.getLong(4)
                            out += MediaFile(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                docId = id, name = name, size = size, modified = mod, kind = kind, relDir = rel,
                            )
                            counter.incrementAndGet()
                        }
                    } ?: throw IllegalStateException("Cartella non leggibile (scheda rimossa?)")
                    ensureActive()
                    for ((id, r) in subdirs) launch { scanDir(id, r) }
                }
            }

            scanDir(rootId, "")
            out.toList()
        }

    /** Raggruppa per cartella + nome senza estensione. */
    fun group(files: List<MediaFile>, tree: Uri): List<Shot> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val vol = rootId.substringBefore(':', "")
        val rootPath = rootId.substringAfter(':', rootId).trim('/')

        class Acc(var jpeg: MediaFile? = null, var heif: MediaFile? = null, var raw: MediaFile? = null)

        val map = LinkedHashMap<String, Acc>()
        val meta = HashMap<String, Pair<String, String>>()
        for (f in files) {
            val path = listOf(rootPath, f.relDir, f.baseName).filter { it.isNotEmpty() }.joinToString("/")
            val key = "$vol:$path".lowercase()
            val acc = map.getOrPut(key) { Acc() }
            meta.getOrPut(key) { f.relDir to f.baseName }
            when (f.kind) {
                Kind.JPEG -> acc.jpeg = f
                Kind.HEIF -> acc.heif = f
                Kind.RAW -> if (acc.raw == null || f.ext == "raf") acc.raw = f
            }
        }
        return map.map { (key, a) ->
            val disp = a.jpeg ?: a.heif
            val (rel, base) = meta[key]!!
            Shot(
                key = key, relDir = rel, baseName = base, jpeg = disp, raw = a.raw,
                modified = (disp ?: a.raw)!!.modified,
            )
        }
    }
}
