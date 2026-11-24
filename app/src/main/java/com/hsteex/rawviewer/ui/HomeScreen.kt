@file:OptIn(ExperimentalMaterial3Api::class)

package com.hsteex.rawviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hsteex.rawviewer.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel) {
    val roots by vm.roots.collectAsStateWithLifecycle()
    val autoAdvance by vm.prefs.autoAdvance.collectAsStateWithLifecycle()
    val swipe by vm.prefs.swipeGestures.collectAsStateWithLifecycle()
    val columns by vm.prefs.columns.collectAsStateWithLifecycle()
    val burstGap by vm.prefs.burstGapMs.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openTree(uri)
    }
    val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.ITALIAN)

    Scaffold(topBar = { TopAppBar(title = { Text("RAW Viewer") }) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Collega la SD con l'adattatore e scegli la cartella DCIM (o una sua sottocartella). " +
                        "Le sottocartelle vengono lette tutte e JPEG e RAW con lo stesso nome diventano un solo scatto.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.SdCard, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scegli cartella", fontSize = 16.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
            if (roots.isNotEmpty()) {
                item { Text("Recenti", style = MaterialTheme.typography.titleMedium) }
                items(roots, key = { it.uri.toString() }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openRoot(r) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = Accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                (r.uri.lastPathSegment ?: "") + " · " + fmt.format(Date(r.lastOpened)),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                        IconButton(onClick = { vm.removeRoot(r) }) { Icon(Icons.Filled.Close, "Rimuovi") }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            item {
                Text("Impostazioni", style = MaterialTheme.typography.titleMedium)
                SettingSwitch("Vai alla foto successiva dopo il voto", autoAdvance) { vm.prefs.setAutoAdvance(it) }
                SettingSwitch("Swipe su = Scarica, giù = Scarta", swipe) { vm.prefs.setSwipe(it) }
                Text("Colonne della griglia", Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 2..6) FilterChip(selected = columns == c, onClick = { vm.prefs.setColumns(c) }, label = { Text("$c") })
                }
                Text("Raffiche: distanza massima tra scatti", Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (g in listOf(500L, 1000L, 1500L, 2500L, 4000L)) {
                        FilterChip(
                            selected = burstGap == g, onClick = { vm.prefs.setBurstGap(g) },
                            label = { Text("%.1fs".format(g / 1000.0)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.repo.clearCaches() }) { Text("Svuota cache miniature") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
