@file:OptIn(ExperimentalMaterial3Api::class)

package com.hsteex.rawviewer.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hsteex.rawviewer.MainViewModel
import com.hsteex.rawviewer.R
import com.hsteex.rawviewer.Screen
import com.hsteex.rawviewer.data.RootEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel) {
    val roots by vm.roots.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openTree(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Impostazioni") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { Header() }
            item { OpenCard(onClick = { picker.launch(null) }) }

            if (roots.isEmpty()) {
                item { HowItWorks() }
            } else {
                item {
                    Text(
                        "Aperte di recente",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
                // lista raggruppata: un blocco unico, angoli grandi solo agli estremi
                itemsIndexed(roots, key = { _, r -> r.uri.toString() }) { i, r ->
                    RecentRow(
                        r, groupShape(i, roots.size),
                        onOpen = { vm.openRoot(r) }, onRemove = { vm.removeRoot(r) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("RAW Viewer", fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
        Text("Sfoglia, valuta e scarica dalla SD", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
    }
}

/** Forma di un elemento in una lista raggruppata (stile impostazioni di Android). */
fun groupShape(index: Int, count: Int): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(24.dp)
    index == 0 -> RoundedCornerShape(24.dp, 24.dp, 6.dp, 6.dp)
    index == count - 1 -> RoundedCornerShape(6.dp, 6.dp, 24.dp, 24.dp)
    else -> RoundedCornerShape(6.dp)
}

@Composable
private fun OpenCard(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(cs.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.SdCard, null, tint = cs.onPrimary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Apri scheda SD", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
            Text("Scegli DCIM o una sua cartella", color = cs.onPrimaryContainer.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onPrimaryContainer)
    }
}

@Composable
private fun HowItWorks() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Surface1)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Come funziona", style = MaterialTheme.typography.titleMedium)
        Step(1, "Collega la SD", "Con l'adattatore USB-C o nello slot del telefono.")
        Step(2, "Scegli la cartella", "Le sottocartelle vengono lette tutte. JPEG e RAW con lo stesso nome diventano un solo scatto.")
        Step(3, "Valuta", "Stelle, ✓ Scarica o ✗ Scarta, anche con swipe su e giù.")
        Step(4, "Scarica", "Copia sul telefono le ✓ in JPEG, RAW o entrambi. Dalla SD non viene mai cancellato niente.")
    }
}

@Composable
private fun Step(n: Int, title: String, text: String) {
    Row {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

@Composable
private fun RecentRow(r: RootEntry, shape: RoundedCornerShape, onOpen: () -> Unit, onRemove: () -> Unit) {
    val (place, path) = describeTree(r.uri)
    val internal = place == "Memoria interna"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (internal) Icons.Filled.PhoneAndroid else Icons.Filled.Folder, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$place · ${path.ifEmpty { "/" }}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(relativeTime(r.lastOpened), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opzioni") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Togli dai recenti") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

/** "Scheda SD" / "Memoria interna" + percorso leggibile, dal document id del tree. */
private fun describeTree(uri: Uri): Pair<String, String> {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return "Cartella" to ""
    val vol = id.substringBefore(':', "")
    val path = id.substringAfter(':', "").trim('/')
    val place = when {
        vol.equals("primary", ignoreCase = true) -> "Memoria interna"
        vol.isNotEmpty() -> "Scheda $vol"
        else -> "Cartella"
    }
    return place to path
}

private fun relativeTime(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val time = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(ms))
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Oggi, $time"
        yesterday -> "Ieri, $time"
        else -> SimpleDateFormat("d MMM yyyy", Locale.ITALIAN).format(Date(ms))
    }
}
