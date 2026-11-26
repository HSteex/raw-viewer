@file:OptIn(ExperimentalMaterial3Api::class)

package com.hsteex.rawviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hsteex.rawviewer.MainViewModel
import com.hsteex.rawviewer.data.DebugLog
import java.io.File

/** Il registro di debug: righe in tempo reale (le più recenti in fondo), da copiare o condividere come file. */
@Composable
fun DebugLogScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val count by DebugLog.count.collectAsStateWithLifecycle()
    val lines = remember(count) { DebugLog.snapshot() }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro (${lines.size})") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = {
                    IconButton(onClick = { copyLog(context) }) { Icon(Icons.Filled.ContentCopy, "Copia") }
                    IconButton(onClick = { shareLog(context) }) { Icon(Icons.Filled.Share, "Condividi") }
                    IconButton(onClick = { DebugLog.clear() }) { Icon(Icons.Filled.DeleteSweep, "Svuota") }
                },
            )
        },
    ) { pad ->
        if (lines.isEmpty()) {
            CenterBox(Modifier.fillMaxSize().padding(pad)) {
                Text(
                    "Ancora vuoto. Usa l'app (apri la scheda, scorri, apri delle foto) e torna qui.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                items(lines) { l ->
                    Text(
                        l, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                        color = if ("⚠" in l || "IMPOSSIBILE" in l) RejectRed else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun dumpText(context: Context): String {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    return DebugLog.dump(version, Runtime.getRuntime().maxMemory() / 1048576)
}

private fun copyLog(context: Context) {
    val text = dumpText(context)
    // gli appunti reggono male testi enormi: si copiano le ultime righe
    val clip = if (text.length > 200_000) text.takeLast(200_000) else text
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("RAW Viewer log", clip))
    Toast.makeText(context, "Registro copiato", Toast.LENGTH_SHORT).show()
}

/** Condivide il registro come file .txt (un testo lungo nell'intent supererebbe i limiti di Android). */
private fun shareLog(context: Context) {
    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
    val f = File(dir, "raw-viewer-log.txt")
    f.writeText(dumpText(context))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    runCatching { context.startActivity(Intent.createChooser(send, "Condividi il registro")) }
}
