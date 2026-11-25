package com.hsteex.rawviewer.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/** Database interno: voti, cache dei metadati e cartelle recenti. */
class Db(context: Context) : SQLiteOpenHelper(context, "rawviewer.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ratings(key TEXT PRIMARY KEY, stars INTEGER NOT NULL DEFAULT 0, " +
                "flag INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, downloaded INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE meta(doc TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, date INTEGER, " +
                "make TEXT, model TEXT, lens TEXT, exposure TEXT, fnum REAL, iso INTEGER, focal REAL, " +
                "bias REAL, w INTEGER, h INTEGER, orient INTEGER, film TEXT, thumbOff INTEGER, " +
                "thumbLen INTEGER, prevOff INTEGER, prevLen INTEGER, jpgOff INTEGER, jpgLen INTEGER)"
        )
        db.execSQL("CREATE TABLE roots(uri TEXT PRIMARY KEY, name TEXT, last INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE ratings ADD COLUMN downloaded INTEGER NOT NULL DEFAULT 0")
        // v3: il parser MPF scartava male le gain map HDR → si rilegge tutto (è solo una cache)
        if (oldVersion < 3) db.execSQL("DELETE FROM meta")
    }

    // ---------- ratings ----------

    fun loadRatings(): Map<String, Rating> {
        val out = HashMap<String, Rating>()
        readableDatabase.rawQuery("SELECT key, stars, flag, downloaded FROM ratings", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = Rating(c.getInt(1), Flag.of(c.getInt(2)), c.getInt(3) != 0)
        }
        return out
    }

    fun saveRatings(changes: Map<String, Rating>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for ((k, r) in changes) {
                if (r.isEmpty) {
                    db.delete("ratings", "key=?", arrayOf(k))
                } else {
                    val v = ContentValues().apply {
                        put("key", k); put("stars", r.stars); put("flag", r.flag.code); put("updated", now)
                        put("downloaded", if (r.downloaded) 1 else 0)
                    }
                    db.insertWithOnConflict("ratings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Segna gli scatti come copiati sul telefono e toglie il ✓ "da scaricare" (stelle e ✗ restano). */
    fun markDownloaded(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (k in keys) {
                db.execSQL(
                    "UPDATE ratings SET downloaded=1, flag=CASE WHEN flag=${Flag.PICK.code} THEN 0 ELSE flag END, updated=? WHERE key=?",
                    arrayOf<Any>(now, k),
                )
                val exists = db.rawQuery("SELECT 1 FROM ratings WHERE key=?", arrayOf(k)).use { it.moveToFirst() }
                if (!exists) {
                    db.insert("ratings", null, ContentValues().apply {
                        put("key", k); put("stars", 0); put("flag", 0); put("updated", now); put("downloaded", 1)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadDownloaded(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT key FROM ratings WHERE downloaded=1", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    // ---------- meta cache ----------

    data class CachedInfo(val size: Long, val mtime: Long, val info: FileInfo)

    fun loadAllInfo(): Map<String, CachedInfo> {
        val out = HashMap<String, CachedInfo>()
        readableDatabase.rawQuery("SELECT * FROM meta", null).use { c ->
            while (c.moveToNext()) out[c.str("doc")!!] = CachedInfo(c.long("size"), c.long("mtime"), c.toInfo())
        }
        return out
    }

    fun putInfos(items: List<Pair<MediaFile, FileInfo>>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((f, i) in items) {
                val m = i.meta
                val v = ContentValues().apply {
                    put("doc", f.docId); put("size", f.size); put("mtime", f.modified)
                    put("date", m.dateTaken); put("make", m.make); put("model", m.model); put("lens", m.lens)
                    put("exposure", m.exposure); put("fnum", m.fNumber); put("iso", m.iso); put("focal", m.focal)
                    put("bias", m.bias); put("w", m.width); put("h", m.height); put("orient", m.orientation)
                    put("film", m.film); put("thumbOff", i.thumbOff); put("thumbLen", i.thumbLen)
                    put("prevOff", i.prevOff); put("prevLen", i.prevLen); put("jpgOff", i.jpgOff); put("jpgLen", i.jpgLen)
                }
                db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.idx(n: String) = getColumnIndexOrThrow(n)
    private fun Cursor.str(n: String): String? = idx(n).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(n: String): Long = idx(n).let { if (isNull(it)) 0L else getLong(it) }
    private fun Cursor.longOrNull(n: String): Long? = idx(n).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.intOrNull(n: String): Int? = idx(n).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.dbl(n: String): Double? = idx(n).let { if (isNull(it)) null else getDouble(it) }

    private fun Cursor.toInfo() = FileInfo(
        meta = PhotoMeta(
            dateTaken = longOrNull("date"), make = str("make"), model = str("model"), lens = str("lens"),
            exposure = str("exposure"), fNumber = dbl("fnum"), iso = intOrNull("iso"), focal = dbl("focal"),
            bias = dbl("bias"), width = intOrNull("w"), height = intOrNull("h"),
            orientation = intOrNull("orient") ?: 1, film = str("film"),
        ),
        thumbOff = long("thumbOff"), thumbLen = long("thumbLen").toInt(),
        prevOff = long("prevOff"), prevLen = long("prevLen").toInt(),
        jpgOff = long("jpgOff"), jpgLen = long("jpgLen").toInt(),
    )

    // ---------- roots ----------

    fun listRoots(): List<RootEntry> {
        val out = ArrayList<RootEntry>()
        readableDatabase.rawQuery("SELECT uri, name, last FROM roots ORDER BY last DESC", null).use { c ->
            while (c.moveToNext()) out += RootEntry(Uri.parse(c.getString(0)), c.getString(1) ?: "", c.getLong(2))
        }
        return out
    }

    fun upsertRoot(uri: Uri, name: String) {
        val v = ContentValues().apply {
            put("uri", uri.toString()); put("name", name); put("last", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("roots", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteRoot(uri: Uri) {
        writableDatabase.delete("roots", "uri=?", arrayOf(uri.toString()))
    }
}
