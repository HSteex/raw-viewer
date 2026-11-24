package com.hsteex.rawviewer.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer

/** Sviluppo vero del RAW (non l'anteprima incorporata) tramite LibRaw. */
object RawDecoder {

    val available: Boolean = try {
        System.loadLibrary("rawdecoder")
        true
    } catch (t: Throwable) {
        Log.w("RawDecoder", "libreria nativa non disponibile", t)
        false
    }

    private external fun nativeDecodeFd(fd: Int, half: Boolean): Bitmap?
    private external fun nativeDecodeBuffer(buffer: ByteBuffer, len: Int, half: Boolean): Bitmap?

    fun decode(context: Context, uri: Uri, half: Boolean = true): Bitmap? {
        if (!available) return null
        val resolver = context.contentResolver
        val viaFd = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd -> nativeDecodeFd(pfd.fd, half) }
        }.getOrNull()
        if (viaFd != null) return viaFd
        // Ripiego: file intero in memoria
        return runCatching {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val ch = input.channel
                val size = ch.size()
                if (size <= 0 || size > Int.MAX_VALUE) return null
                val buf = ByteBuffer.allocateDirect(size.toInt())
                while (buf.hasRemaining()) if (ch.read(buf) <= 0) break
                nativeDecodeBuffer(buf, buf.position(), half)
            }
        }.getOrNull()
    }
}
