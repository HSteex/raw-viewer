package com.hsteex.rawviewer.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copia in background dalla SD a Immagini/RAW Viewer/<data>/ tramite MediaStore. */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    data class Item(val uri: String, val name: String, val size: Long, val relPath: String, val time: Long)

    private val resolver = context.contentResolver
    private val nm = context.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val jobPath = inputData.getString(KEY_JOB) ?: return Result.failure()
        val items = readJob(File(jobPath))
        val total = items.size
        runCatching { setForeground(foregroundInfo(0, total)) }

        var copied = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        for ((i, it) in items.withIndex()) {
            if (isStopped) break
            when (copyOne(it)) {
                Outcome.COPIED -> { copied++; bytes += it.size }
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
            }
            setProgress(workDataOf(KEY_DONE to i + 1, KEY_TOTAL to total, KEY_FAILED to failed))
            runCatching { nm.notify(NOTIF_ID, buildNotification(i + 1, total)) }
        }
        File(jobPath).delete()
        nm.notify(
            NOTIF_DONE_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Copia completata")
                .setContentText("$copied copiati · $skipped già presenti" + if (failed > 0) " · $failed errori" else "")
                .setAutoCancel(true)
                .build()
        )
        return Result.success(
            workDataOf(KEY_DONE to total, KEY_TOTAL to total, KEY_COPIED to copied, KEY_SKIPPED to skipped, KEY_FAILED to failed)
        )
    }

    private enum class Outcome { COPIED, SKIPPED, FAILED }

    private fun copyOne(item: Item): Outcome {
        val src = Uri.parse(item.uri)
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mime = mimeFor(ext)
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        if (exists(images, item.relPath, item.name, item.size)) return Outcome.SKIPPED

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, item.relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (item.time > 0) put(MediaStore.Images.ImageColumns.DATE_TAKEN, item.time)
        }
        var dest: Uri? = runCatching { resolver.insert(images, values) }.getOrNull()
        if (dest == null) {
            // Alcuni dispositivi rifiutano i MIME RAW nella raccolta Immagini: ripiego su Download.
            val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val rel = item.relPath.replaceFirst("Pictures/", "Download/")
            if (exists(downloads, rel, item.name, item.size)) return Outcome.SKIPPED
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            values.remove(MediaStore.Images.ImageColumns.DATE_TAKEN)
            dest = runCatching { resolver.insert(downloads, values) }.getOrNull()
        }
        val target: Uri = dest ?: return Outcome.FAILED
        return try {
            resolver.openInputStream(src).use { input ->
                resolver.openOutputStream(target, "w").use { out ->
                    requireNotNull(input); requireNotNull(out)
                    input.copyTo(out, 1 shl 20)
                }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Outcome.COPIED
        } catch (e: Exception) {
            Log.w("ImportWorker", "copy ${item.name}", e)
            runCatching { resolver.delete(target, null, null) }
            Outcome.FAILED
        }
    }

    private fun exists(collection: Uri, rel: String, name: String, size: Long): Boolean {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        return runCatching {
            resolver.query(collection, proj, sel, arrayOf(rel, name), null)?.use { c ->
                var found = false
                while (c.moveToNext()) if (c.getLong(1) == size) found = true
                found
            } ?: false
        }.getOrDefault(false)
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo =
        ForegroundInfo(NOTIF_ID, buildNotification(done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun buildNotification(done: Int, total: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Copia dalla SD")
            .setContentText(if (total > 0) "$done di $total file" else "Preparazione…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        const val UNIQUE = "import"
        const val CHANNEL = "import"
        const val NOTIF_ID = 42
        const val NOTIF_DONE_ID = 43
        const val KEY_JOB = "job"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_COPIED = "copied"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val BASE_DIR = "Pictures/RAW Viewer"

        private val RAW_MIME = mapOf(
            "raf" to "image/x-fuji-raf", "dng" to "image/x-adobe-dng", "nef" to "image/x-nikon-nef",
            "nrw" to "image/x-nikon-nrw", "arw" to "image/x-sony-arw", "cr2" to "image/x-canon-cr2",
            "cr3" to "image/x-canon-cr3", "orf" to "image/x-olympus-orf", "rw2" to "image/x-panasonic-rw2",
            "pef" to "image/x-pentax-pef", "srw" to "image/x-samsung-srw",
        )

        fun mimeFor(ext: String): String = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "hif", "heif", "heic" -> "image/heif"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: RAW_MIME[ext] ?: "image/x-raw"
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Copia foto", NotificationManager.IMPORTANCE_LOW))
        }

        /** Costruisce la lista dei file da copiare e avvia il lavoro. Ritorna il numero di file. */
        fun enqueue(context: Context, shots: List<Shot>, format: ImportFormat, metaTime: (Shot) -> Long): Int {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val arr = JSONArray()
            for (s in shots) {
                val time = metaTime(s)
                val rel = "$BASE_DIR/${day.format(Date(time))}/"
                val files = when (format) {
                    ImportFormat.JPEG -> listOfNotNull(s.jpeg)
                    ImportFormat.RAW -> listOfNotNull(s.raw)
                    ImportFormat.BOTH -> listOfNotNull(s.jpeg, s.raw)
                }
                for (f in files) {
                    arr.put(JSONObject().apply {
                        put("uri", f.uri.toString()); put("name", f.name); put("size", f.size)
                        put("rel", rel); put("time", time)
                    })
                }
            }
            if (arr.length() == 0) return 0
            val dir = File(context.filesDir, "jobs").apply { mkdirs() }
            val job = File(dir, "job-${System.currentTimeMillis()}.json")
            job.writeText(arr.toString())
            val req = OneTimeWorkRequestBuilder<ImportWorker>()
                .setInputData(workDataOf(KEY_JOB to job.absolutePath))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            return arr.length()
        }

        private fun readJob(f: File): List<Item> {
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(o.getString("uri"), o.getString("name"), o.getLong("size"), o.getString("rel"), o.optLong("time"))
            }
        }
    }
}
