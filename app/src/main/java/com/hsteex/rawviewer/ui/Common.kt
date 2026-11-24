package com.hsteex.rawviewer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hsteex.rawviewer.data.Flag
import com.hsteex.rawviewer.data.ImageRepo
import com.hsteex.rawviewer.data.ImportFormat
import com.hsteex.rawviewer.data.PhotoMeta
import com.hsteex.rawviewer.data.Shot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Miniatura della griglia: subito quella EXIF (sfocata ma quasi gratis), poi quella vera. */
@Composable
fun rememberGridThumb(repo: ImageRepo, shot: Shot): Bitmap? {
    val ready = remember(shot.key) { repo.peekGrid(shot) }
    var bmp by remember(shot.key) { mutableStateOf(ready ?: repo.peekQuick(shot)) }
    if (ready == null) {
        LaunchedEffect(shot.key) {
            val quick = if (bmp == null) launch { repo.quickThumb(shot)?.let { q -> if (bmp == null) bmp = q } } else null
            repo.grid(shot)?.let { quick?.cancel(); bmp = it }
        }
    }
    return bmp
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.6f)) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun StarsRow(stars: Int, onStar: (Int) -> Unit, size: Dp = 34.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            Icon(
                if (i <= stars) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "$i stelle",
                tint = if (i <= stars) StarYellow else Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .clickable { onStar(i) }
                    .padding(4.dp),
            )
        }
    }
}

@Composable
fun FlagButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (active) activeColor else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (active) Color.Black else Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PickRejectButtons(flag: Flag, onPick: () -> Unit, onReject: () -> Unit, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FlagButton(if (compact) "" else "Scarta", Icons.Filled.Close, flag == Flag.REJECT, RejectRed, onReject)
        FlagButton(if (compact) "" else "Scarica", Icons.Filled.Check, flag == Flag.PICK, PickGreen, onPick)
    }
}

@Composable
fun FlagMarker(flag: Flag, modifier: Modifier = Modifier) {
    when (flag) {
        Flag.PICK -> Icon(
            Icons.Filled.Check, "Scarica", tint = Color.Black,
            modifier = modifier.size(20.dp).clip(CircleShape).background(PickGreen).padding(2.dp),
        )
        Flag.REJECT -> Icon(
            Icons.Filled.Close, "Scartata", tint = Color.White,
            modifier = modifier.size(20.dp).clip(CircleShape).background(RejectRed).padding(2.dp),
        )
        Flag.NONE -> {}
    }
}

/** Scatto già copiato sul telefono. */
@Composable
fun DownloadedMarker(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.DownloadDone, "Già scaricata", tint = Color.Black,
        modifier = modifier.size(20.dp).clip(CircleShape).background(DownloadedBlue).padding(3.dp),
    )
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

private val dateFmt = SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss", Locale.ITALIAN)

@Composable
fun InfoContent(shot: Shot, meta: PhotoMeta?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(shot.baseName, style = MaterialTheme.typography.titleLarge)
        Text(shot.relDir.ifEmpty { "/" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        val m = meta
        if (m != null) {
            val expo = listOfNotNull(
                m.exposure,
                m.fNumber?.let { "f/" + trimNum(it) },
                m.iso?.let { "ISO $it" },
                m.focal?.let { trimNum(it) + " mm" },
                m.bias?.takeIf { kotlin.math.abs(it) > 0.01 }?.let { (if (it > 0) "+" else "") + "%.1f EV".format(it) },
            )
            if (expo.isNotEmpty()) InfoRow("Esposizione", expo.joinToString("  ·  "))
            m.film?.let { InfoRow("Simulazione", it) }
            m.dateTaken?.let { InfoRow("Scattata", dateFmt.format(Date(it))) }
            val cam = listOfNotNull(m.make?.trim(), m.model?.trim()).joinToString(" ").ifEmpty { null }
            cam?.let { InfoRow("Fotocamera", it) }
            m.lens?.let { InfoRow("Obiettivo", it) }
            if (m.width != null && m.height != null) {
                InfoRow("Dimensioni", "${m.width} × ${m.height}  (${"%.1f".format(m.width * m.height / 1e6)} MP)")
            }
        } else {
            Text("Metadati in lettura…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        shot.jpeg?.let { InfoRow("File", "${it.name}  ·  ${formatSize(it.size)}") }
        shot.raw?.let { InfoRow(if (shot.jpeg == null) "File" else "RAW", "${it.name}  ·  ${formatSize(it.size)}") }
        Spacer(Modifier.height(24.dp))
    }
}

private fun trimNum(d: Double): String =
    if (d == Math.rint(d)) d.toLong().toString() else "%.1f".format(d)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp), fontSize = 14.sp)
        Text(value, fontSize = 14.sp)
    }
}

/** Dialogo di copia sul telefono: scelta JPEG / RAW / entrambi. */
@Composable
fun ImportDialog(
    shots: List<Shot>,
    initial: ImportFormat,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (ImportFormat) -> Unit,
) {
    var format by remember { mutableStateOf(initial) }
    val jpegs = shots.mapNotNull { it.jpeg }
    val raws = shots.mapNotNull { it.raw }
    val files = when (format) {
        ImportFormat.JPEG -> jpegs
        ImportFormat.RAW -> raws
        ImportFormat.BOTH -> jpegs + raws
    }
    val missing = when (format) {
        ImportFormat.JPEG -> shots.count { it.jpeg == null }
        ImportFormat.RAW -> shots.count { it.raw == null }
        ImportFormat.BOTH -> 0
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("${shots.size} scatti", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                for (f in ImportFormat.entries) {
                    val count = when (f) {
                        ImportFormat.JPEG -> jpegs.size
                        ImportFormat.RAW -> raws.size
                        ImportFormat.BOTH -> jpegs.size + raws.size
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = format == f, onClick = { format = f }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text("${f.label}  ($count file)")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("${files.size} file · ${formatSize(files.sumOf { it.size })}")
                if (missing > 0) {
                    Text(
                        "$missing scatti non hanno questo formato e verranno saltati",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Destinazione: Immagini/RAW Viewer/<data di scatto>. I file già copiati vengono saltati. Niente viene cancellato dalla SD.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = files.isNotEmpty(), onClick = { onConfirm(format) }) { Text("Scarica sul telefono") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
fun CenterBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}
