@file:OptIn(ExperimentalMaterial3Api::class)

package com.hsteex.rawviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hsteex.rawviewer.MainViewModel
import com.hsteex.rawviewer.data.ImportFormat
import com.hsteex.rawviewer.data.RawDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val p = vm.prefs
    val autoAdvance by p.autoAdvance.collectAsStateWithLifecycle()
    val swipe by p.swipeGestures.collectAsStateWithLifecycle()
    val columns by p.columns.collectAsStateWithLifecycle()
    val burstGap by p.burstGapMs.collectAsStateWithLifecycle()
    val format by p.importFormat.collectAsStateWithLifecycle()
    val timings by p.showTimings.collectAsStateWithLifecycle()
    val dynamic by p.dynamicColor.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cacheSize by remember { mutableLongStateOf(-1L) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { vm.repo.diskCacheSize() } }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Impostazioni", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Section("Aspetto") {
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow(
                        "Colori dallo sfondo",
                        "Material You: i colori dell'app seguono il tuo sfondo. Spento: ambra.",
                        dynamic, p::setDynamicColor,
                    )
                } else {
                    LabeledRow("Colori dallo sfondo", "Serve Android 12 o successivo")
                }
                Divider()
                LabeledRow("Tema", "Sempre scuro: le foto si giudicano meglio su un fondo neutro")
            }

            Section("Valutazione") {
                SwitchRow(
                    "Avanza dopo il voto",
                    "Dopo stelle, Scarica o Scarta passa alla foto successiva",
                    autoAdvance, p::setAutoAdvance,
                )
                Divider()
                SwitchRow(
                    "Gesti di swipe",
                    "Nel visualizzatore: su = Scarica, giù = Scarta",
                    swipe, p::setSwipe,
                )
            }

            Section("Diagnostica") {
                SwitchRow(
                    "Mostra tempi di caricamento",
                    "Nel visualizzatore: quanto ci mettono anteprima e piena risoluzione",
                    timings, p::setShowTimings,
                )
            }

            Section("Griglia") {
                LabeledRow("Colonne", "Quante miniature per riga")
                Segmented(
                    options = (2..6).toList(), selected = columns,
                    label = { "$it" }, onSelect = p::setColumns,
                )
            }

            Section("Raffiche") {
                LabeledRow(
                    "Distanza massima tra scatti",
                    "Almeno 3 scatti più vicini di così diventano una pila (icona ▣ nella griglia)",
                )
                Segmented(
                    options = listOf(500L, 1000L, 1500L, 2500L, 4000L), selected = burstGap,
                    label = { "%.1fs".format(it / 1000.0) }, onSelect = p::setBurstGap,
                )
            }

            Section("Copia sul telefono") {
                LabeledRow("Formato predefinito", "Lo puoi cambiare ogni volta prima di scaricare")
                Segmented(
                    options = ImportFormat.entries.toList(), selected = format,
                    label = {
                        when (it) {
                            ImportFormat.JPEG -> "JPEG"
                            ImportFormat.RAW -> "RAW"
                            ImportFormat.BOTH -> "Entrambi"
                        }
                    },
                    onSelect = p::setImportFormat,
                )
                Divider()
                LabeledRow("Destinazione", "Immagini/RAW Viewer/<data di scatto>")
            }

            Section("Archiviazione") {
                Row(
                    Modifier.fillMaxWidth().background(Surface1).clickable { confirmClear = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Svuota cache anteprime", fontWeight = FontWeight.Medium)
                        Text(
                            if (cacheSize < 0) "Calcolo…" else "Occupa ${formatSize(cacheSize)}. I voti non vengono toccati.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("Informazioni") {
                LabeledRow("Versione", appVersion(context))
                Divider()
                LabeledRow(
                    "Sviluppo RAW",
                    if (RawDecoder.available) "LibRaw attiva (licenza LGPL 2.1 / CDDL)" else "Non disponibile su questo dispositivo",
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Svuotare la cache?") },
            text = { Text("Le miniature verranno rigenerate la prossima volta che apri la scheda. Voti e foto copiate restano.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { vm.repo.clearCaches() }
                        cacheSize = 0
                    }
                }) { Text("Svuota") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        // lista raggruppata: ogni riga è una piastrella, separate da una fessura; angoli grandi solo fuori
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)), content = content)
    }
}

@Composable
private fun Divider() = Spacer(Modifier.fillMaxWidth().height(3.dp))

@Composable
private fun SwitchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Surface1).clickable { onChange(!value) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledRow(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().background(Surface1).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().background(Surface1).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                label = { Text(label(o), maxLines = 1, fontSize = 13.sp) },
            )
        }
    }
}

private fun appVersion(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}.getOrDefault("?")
