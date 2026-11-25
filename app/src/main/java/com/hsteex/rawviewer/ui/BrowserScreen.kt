@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.hsteex.rawviewer.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.hsteex.rawviewer.MainViewModel
import com.hsteex.rawviewer.ScanState
import com.hsteex.rawviewer.data.Flag
import com.hsteex.rawviewer.data.FlagFilter
import com.hsteex.rawviewer.data.GridItem
import com.hsteex.rawviewer.data.ImageRepo
import com.hsteex.rawviewer.data.ImportFormat
import com.hsteex.rawviewer.data.ImportWorker
import com.hsteex.rawviewer.data.Rating
import com.hsteex.rawviewer.data.Shot
import com.hsteex.rawviewer.data.Sort
import com.hsteex.rawviewer.data.TypeFilter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay

private class ImportRequest(val shots: List<Shot>, val title: String)

@Composable
fun BrowserScreen(vm: MainViewModel) {
    val root by vm.root.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val items by vm.gridItems.collectAsStateWithLifecycle()
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    val allShots by vm.shots.collectAsStateWithLifecycle()
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val columns by vm.prefs.columns.collectAsStateWithLifecycle()
    val importFormat by vm.prefs.importFormat.collectAsStateWithLifecycle()
    val indexing by vm.indexing.collectAsStateWithLifecycle()
    val work by vm.importWork.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val gridState = vm.gridState
    // al ritorno dal visualizzatore: se la foto vista non è a schermo, ci si porta lì
    LaunchedEffect(gridState) {
        val k = vm.lastViewedKey ?: return@LaunchedEffect
        vm.lastViewedKey = null
        val idx = items.indexOfFirst { it.key == k || (it is GridItem.Burst && it.shots.any { s -> s.key == k }) }
        if (idx < 0) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.totalItemsCount }.first { it > 0 }
        val info = gridState.layoutInfo
        val cell = info.visibleItemsInfo.firstOrNull { it.index == idx }
        val fullyVisible = cell != null && cell.offset.y >= info.viewportStartOffset &&
            cell.offset.y + cell.size.height <= info.viewportEndOffset - info.afterContentPadding
        if (!fullyVisible) gridState.scrollToItem((idx - columns).coerceAtLeast(0))
    }
    val scope = rememberCoroutineScope()

    var importReq by remember { mutableStateOf<ImportRequest?>(null) }
    val dismissedWork by vm.dismissedImport.collectAsStateWithLifecycle()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val picked = remember(allShots, ratings) { allShots.count { ratings[it.key]?.flag == Flag.PICK } }
    val rejected = remember(allShots, ratings) { allShots.count { ratings[it.key]?.flag == Flag.REJECT } }
    val selecting = selection.isNotEmpty()

    fun share(list: List<Shot>) {
        val i = vm.shareIntent(list) ?: return
        runCatching { context.startActivity(i) }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} selezionati") },
                    navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, "Annulla selezione") } },
                    actions = {
                        IconButton(onClick = { vm.selectAllFiltered() }) { Icon(Icons.Filled.SelectAll, "Seleziona tutti i filtrati") }
                        IconButton(onClick = { share(vm.selectedShots()) }) { Icon(Icons.Filled.Share, "Condividi") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface2),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(root?.name ?: "", maxLines = 1)
                            Text(
                                "${allShots.size} scatti · ✓ $picked · ✗ $rejected" +
                                    if (!filter.isDefault) " · filtrati ${filtered.size}" else "",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                    actions = {
                        IconButton(onClick = { vm.filter.value = filter.copy(groupBursts = !filter.groupBursts) }) {
                            Icon(
                                Icons.Filled.BurstMode, "Raggruppa raffiche",
                                tint = if (filter.groupBursts) Accent else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        SortMenu(filter.sort) { vm.filter.value = filter.copy(sort = it) }
                        var more by remember { mutableStateOf(false) }
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(
                                text = { Text("Seleziona tutti i filtrati (${filtered.size})") },
                                leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                onClick = { more = false; vm.selectAllFiltered() },
                            )
                            DropdownMenuItem(
                                text = { Text("Rileggi la cartella") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { more = false; vm.startScan() },
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    count = selection.size,
                    onPick = { vm.setFlag(selection, Flag.PICK) },
                    onReject = { vm.setFlag(selection, Flag.REJECT) },
                    onClearFlag = { vm.setFlag(selection, Flag.NONE) },
                    onStars = { vm.setStars(selection, it) },
                    onCompare = {
                        val s = vm.selectedShots()
                        if (s.size == 2) vm.openCompare(s[0].key, s[1].key, filtered.map { it.key })
                    },
                    onDownload = { importReq = ImportRequest(vm.selectedShots(), "Scarica selezionati") },
                )
            }
        },
        floatingActionButton = {
            if (!selecting && picked > 0) {
                ExtendedFloatingActionButton(
                    onClick = { importReq = ImportRequest(vm.pickedShots(), "Scarica le foto ✓") },
                    icon = { Icon(Icons.Filled.Download, null) },
                    text = { Text("Scarica $picked") },
                    containerColor = PickGreen,
                    contentColor = Color.Black,
                )
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // stato scansione / indicizzazione
            when (val s = scan) {
                is ScanState.Scanning -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    StatusText("Lettura della scheda… ${s.files} file")
                }
                is ScanState.Error -> StatusText("Errore: ${s.message}", color = RejectRed)
                is ScanState.Done -> {
                    val idx = indexing
                    if (idx != null) {
                        LinearProgressIndicator(
                            progress = { if (idx.second == 0) 0f else idx.first.toFloat() / idx.second },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        StatusText("${s.shots} scatti in %.1f s · metadati ${idx.first}/${idx.second}".format(s.ms / 1000.0))
                    }
                }
                ScanState.Idle -> {}
            }
            ImportStatus(work, dismissedWork, onCancel = { vm.cancelImport() }, onDismiss = { vm.dismissImport(it) })

            FilterBar(
                filter = filter, folders = folders,
                onChange = { vm.filter.value = it },
            )

            if (items.isEmpty() && scan is ScanState.Done) {
                CenterBox(Modifier.fillMaxSize()) {
                    Text(
                        if (allShots.isEmpty()) "Nessun JPEG o RAW in questa cartella" else "Nessuno scatto con questi filtri",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 3.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(items, key = { it.key }, contentType = { if (it is GridItem.Burst) 1 else 0 }) { item ->
                    when (item) {
                        is GridItem.Single -> {
                            val s = item.shot
                            ShotCell(
                                shot = s, rating = ratings[s.key] ?: Rating(), selected = s.key in selection,
                                repo = vm.repo, burstCount = null,
                                onClick = {
                                    if (selecting) vm.toggleSelect(listOf(s.key))
                                    else {
                                        val keys = filtered.map { it.key }
                                        vm.openViewer(keys, keys.indexOf(s.key))
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelect(listOf(s.key))
                                },
                            )
                        }
                        is GridItem.Burst -> {
                            val keys = item.shots.map { it.key }
                            val best = item.shots.maxByOrNull { sh ->
                                val r = ratings[sh.key] ?: Rating()
                                (if (r.flag == Flag.PICK) 10 else 0) + r.stars
                            } ?: item.cover
                            ShotCell(
                                shot = best, rating = ratings[best.key] ?: Rating(),
                                selected = keys.all { it in selection }, repo = vm.repo, burstCount = item.shots.size,
                                onClick = {
                                    if (selecting) vm.toggleSelect(keys) else vm.openViewer(keys, keys.indexOf(best.key))
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelect(keys)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    importReq?.let { req ->
        ImportDialog(
            shots = req.shots, initial = importFormat, title = req.title,
            onDismiss = { importReq = null },
            onConfirm = { fmt ->
                importReq = null
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                val n = vm.startImport(req.shots, fmt)
                Toast.makeText(context, "Copia di $n file avviata", Toast.LENGTH_SHORT).show()
                if (selecting) vm.clearSelection()
            },
        )
    }
}

private val GrayFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun StatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun ImportStatus(work: WorkInfo?, dismissed: String?, onCancel: () -> Unit, onDismiss: (String) -> Unit) {
    if (work == null || work.id.toString() == dismissed) return
    val p = if (work.state.isFinished) work.outputData else work.progress
    val done = p.getInt(ImportWorker.KEY_DONE, 0)
    val total = p.getInt(ImportWorker.KEY_TOTAL, 0)
    // a copia riuscita senza errori il banner si chiude da solo; con errori o annullata resta finché non premi OK
    if (work.state == WorkInfo.State.SUCCEEDED && p.getInt(ImportWorker.KEY_FAILED, 0) == 0) {
        LaunchedEffect(work.id) {
            delay(4000)
            onDismiss(work.id.toString())
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (work.state.isFinished) Icons.Filled.DownloadDone else Icons.Filled.Download, null,
            tint = when (work.state) {
                WorkInfo.State.SUCCEEDED -> PickGreen
                WorkInfo.State.FAILED -> RejectRed
                WorkInfo.State.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> DownloadedBlue
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when (work.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    Text(if (total > 0) "Copia sul telefono: $done di $total" else "Copia in preparazione…", fontSize = 13.sp)
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(2.dp)),
                        color = DownloadedBlue,
                    )
                }
                WorkInfo.State.SUCCEEDED -> {
                    val c = p.getInt(ImportWorker.KEY_COPIED, 0)
                    val sk = p.getInt(ImportWorker.KEY_SKIPPED, 0)
                    val f = p.getInt(ImportWorker.KEY_FAILED, 0)
                    Text(
                        "✓ Copiati $c file" + (if (sk > 0) " · $sk già presenti" else "") + (if (f > 0) " · $f errori" else ""),
                        fontSize = 13.sp, color = if (f > 0) RejectRed else PickGreen,
                    )
                }
                WorkInfo.State.CANCELLED -> Text("Copia annullata", fontSize = 13.sp)
                WorkInfo.State.FAILED -> Text("Copia non riuscita", fontSize = 13.sp, color = RejectRed)
            }
        }
        if (work.state.isFinished) {
            TextButton(onClick = { onDismiss(work.id.toString()) }) { Text("OK") }
        } else {
            TextButton(onClick = onCancel) { Text("Annulla") }
        }
    }
}

@Composable
private fun SortMenu(current: Sort, onSort: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordina") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (s in Sort.entries) {
            DropdownMenuItem(
                text = { Text(s.label, fontWeight = if (s == current) FontWeight.Bold else FontWeight.Normal) },
                leadingIcon = { if (s == current) Icon(Icons.Filled.Check, null) },
                onClick = { open = false; onSort(s) },
            )
        }
    }
}

@Composable
private fun FilterBar(filter: com.hsteex.rawviewer.data.Filter, folders: List<String>, onChange: (com.hsteex.rawviewer.data.Filter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(FlagFilter.entries.size) { i ->
            val f = FlagFilter.entries[i]
            ColorChip(f.label, filter.flag == f, f.color()) { onChange(filter.copy(flag = f)) }
        }
        item {
            ChipMenu(
                label = if (filter.minStars == 0) "★ Stelle" else "★ ${filter.minStars}+",
                selected = filter.minStars > 0,
                color = StarYellow,
                options = listOf("Qualsiasi") + (1..5).map { "★".repeat(it) + if (it < 5) " o più" else "" },
                onPick = { onChange(filter.copy(minStars = it)) },
            )
        }
        item {
            ChipMenu(
                label = filter.type.label, selected = filter.type != TypeFilter.ALL,
                options = TypeFilter.entries.map { it.label },
                onPick = { onChange(filter.copy(type = TypeFilter.entries[it])) },
            )
        }
        if (folders.size > 1) {
            item {
                ChipMenu(
                    label = filter.folder?.ifEmpty { "/" } ?: "Tutte le cartelle", selected = filter.folder != null,
                    options = listOf("Tutte le cartelle") + folders.map { it.ifEmpty { "/" } },
                    onPick = { onChange(filter.copy(folder = if (it == 0) null else folders[it - 1])) },
                )
            }
        }
    }
}

/** Colore del filtro: lo stesso dei segni nella griglia (✓ verde, ✗ rosso, scaricate blu). */
private fun FlagFilter.color(): Color = when (this) {
    FlagFilter.ALL -> Accent
    FlagFilter.UNRATED -> Color(0xFFE8E3DC)
    FlagFilter.PICK -> PickGreen
    FlagFilter.REJECT -> RejectRed
    FlagFilter.NOT_REJECTED -> Color(0xFFB8E6C0)
    FlagFilter.DOWNLOADED -> DownloadedBlue
}

/** Chip di filtro: quando è attivo prende il suo colore (sfondo tenue, testo e bordo pieni). */
@Composable
private fun ColorChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = color.copy(alpha = 0.85f),
            selectedContainerColor = color.copy(alpha = 0.22f),
            selectedLabelColor = color,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = color.copy(alpha = 0.35f),
            selectedBorderColor = color,
            selectedBorderWidth = 1.dp,
        ),
    )
}

@Composable
private fun ChipMenu(label: String, selected: Boolean, options: List<String>, color: Color = Accent, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ColorChip("$label ▾", selected, color) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}

@Composable
private fun ShotCell(
    shot: Shot,
    rating: Rating,
    selected: Boolean,
    repo: ImageRepo,
    burstCount: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val rejected = rating.flag == Flag.REJECT
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface1)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val bmp = rememberGridThumb(repo, shot)
        if (bmp != null) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(
                img, shot.baseName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alpha = if (rejected) 0.4f else 1f,
                colorFilter = if (rejected) GrayFilter else null,
            )
        }
        // velo in basso per leggere stelle e raffica su qualsiasi foto
        if (rating.stars > 0 || burstCount != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.32f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
            )
        }
        Badge(shot.badge, Modifier.align(Alignment.TopStart).padding(4.dp))
        Row(
            Modifier.align(Alignment.TopEnd).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (rating.downloaded) DownloadedMarker()
            FlagMarker(rating.flag)
        }
        if (rating.stars > 0) {
            Text(
                "★".repeat(rating.stars), color = StarYellow, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 5.dp, vertical = 3.dp),
            )
        }
        if (burstCount != null) {
            Badge("▣ $burstCount", Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Accent.copy(alpha = 0.9f))
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(3.dp, Accent, RoundedCornerShape(6.dp)).background(Accent.copy(alpha = 0.18f)))
            Icon(
                Icons.Filled.CheckCircle, null, tint = Accent,
                modifier = Modifier.align(Alignment.Center).size(32.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape),
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onPick: () -> Unit,
    onReject: () -> Unit,
    onClearFlag: () -> Unit,
    onStars: (Int) -> Unit,
    onCompare: () -> Unit,
    onDownload: () -> Unit,
) {
    BottomAppBar(containerColor = Surface2) {
        IconButton(onClick = onReject) { Icon(Icons.Filled.Close, "Scarta", tint = RejectRed) }
        IconButton(onClick = onPick) { Icon(Icons.Filled.Check, "Scarica", tint = PickGreen) }
        var stars by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { stars = true }) { Icon(Icons.Filled.Star, "Stelle", tint = StarYellow) }
            DropdownMenu(expanded = stars, onDismissRequest = { stars = false }) {
                DropdownMenuItem(text = { Text("Togli stelle") }, onClick = { stars = false; onStars(0) })
                for (i in 1..5) DropdownMenuItem(text = { Text("★".repeat(i)) }, onClick = { stars = false; onStars(i) })
                DropdownMenuItem(text = { Text("Togli ✓/✗") }, onClick = { stars = false; onClearFlag() })
            }
        }
        IconButton(onClick = onCompare, enabled = count == 2) { Icon(Icons.Filled.Compare, "Confronta (2)") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, null)
            Spacer(Modifier.width(4.dp))
            Text("Scarica")
        }
    }
}
