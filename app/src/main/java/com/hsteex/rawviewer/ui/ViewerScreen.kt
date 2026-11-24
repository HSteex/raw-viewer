@file:OptIn(ExperimentalMaterial3Api::class)

package com.hsteex.rawviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hsteex.rawviewer.MainViewModel
import com.hsteex.rawviewer.Screen
import com.hsteex.rawviewer.data.Flag
import com.hsteex.rawviewer.data.ImageRepo
import com.hsteex.rawviewer.data.Rating
import com.hsteex.rawviewer.data.Shot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.abs

@Composable
fun ViewerScreen(vm: MainViewModel, screen: Screen.Viewer) {
    val shotMap by vm.shotByKey.collectAsStateWithLifecycle()
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    val metas by vm.metas.collectAsStateWithLifecycle()
    val autoAdvance by vm.prefs.autoAdvance.collectAsStateWithLifecycle()
    val swipe by vm.prefs.swipeGestures.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val keys = screen.keys
    val pager = rememberPagerState(initialPage = screen.start) { keys.size }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val currentKey = keys.getOrNull(pager.currentPage)
    val current = currentKey?.let { shotMap[it] }
    val rating = currentKey?.let { ratings[it] } ?: Rating()

    LaunchedEffect(pager.currentPage, shotMap) {
        val p = pager.currentPage
        vm.repo.prefetch(listOf(p + 1, p - 1, p + 2).mapNotNull { keys.getOrNull(it)?.let(shotMap::get) })
    }

    fun advance() {
        if (autoAdvance && pager.currentPage < keys.lastIndex) {
            scope.launch {
                delay(150)
                pager.animateScrollToPage(pager.currentPage + 1)
            }
        }
    }

    fun flag(key: String, f: Flag) {
        val before = vm.rating(key).flag
        vm.toggleFlag(key, f)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (before != f) advance()
    }

    fun stars(key: String, n: Int) {
        val before = vm.rating(key).stars
        vm.toggleStars(key, n)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (before != n) advance()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { keys[it] },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val s = shotMap[keys[page]]
            if (s != null) {
                ViewerPage(
                    repo = vm.repo, shot = s,
                    raw = showRaw && vm.repo.canDevelopRaw(s),
                    onTap = { chrome = !chrome },
                    swipeEnabled = swipe,
                    onSwipe = { up -> flag(s.key, if (up) Flag.PICK else Flag.REJECT) },
                )
            } else {
                CenterBox(Modifier.fillMaxSize()) { Text("File non più disponibile", color = Color.White) }
            }
        }

        // indicatore dello stato anche a interfaccia nascosta
        if (!chrome && rating.flag != Flag.NONE) {
            FlagMarker(rating.flag, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current?.baseName ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "${pager.currentPage + 1} / ${keys.size}" + (current?.let { "  ·  " + it.badge } ?: ""),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    )
                }
                if (canUndo) IconButton(onClick = { vm.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Annulla voto", tint = Color.White) }
                if (pager.currentPage < keys.lastIndex) {
                    IconButton(onClick = { currentKey?.let { vm.openCompare(it, keys[pager.currentPage + 1]) } }) {
                        Icon(Icons.Filled.Compare, "Confronta con la successiva", tint = Color.White)
                    }
                }
                IconButton(onClick = {
                    current?.let { s -> vm.shareIntent(listOf(s))?.let { runCatching { context.startActivity(it) } } }
                }) { Icon(Icons.Filled.Share, "Condividi", tint = Color.White) }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "Info", tint = Color.White) }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .navigationBarsPadding()
                    .padding(top = 24.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                metas[currentKey]?.let { m ->
                    val line = listOfNotNull(m.exposure, m.fNumber?.let { "f/%.1f".format(it) }, m.iso?.let { "ISO $it" }, m.film)
                    if (line.isNotEmpty()) {
                        Text(line.joinToString("  ·  "), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (current != null && vm.repo.canDevelopRaw(current)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.12f)),
                    ) {
                        for ((label, raw) in listOf((if (current.jpeg != null) "JPEG" else "Anteprima") to false, "RAW" to true)) {
                            val sel = showRaw == raw
                            Text(
                                label,
                                color = if (sel) Color.Black else Color.White,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (sel) Accent else Color.Transparent)
                                    .clickable { showRaw = raw }
                                    .padding(horizontal = 18.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                StarsRow(rating.stars, onStar = { n -> currentKey?.let { stars(it, n) } }, size = 40.dp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FlagButton("Scarta", Icons.Filled.Close, rating.flag == Flag.REJECT, RejectRed, {
                        currentKey?.let { flag(it, Flag.REJECT) }
                    })
                    FlagButton("Scarica", Icons.Filled.Check, rating.flag == Flag.PICK, PickGreen, {
                        currentKey?.let { flag(it, Flag.PICK) }
                    })
                }
            }
        }
    }

    if (showInfo && current != null) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, containerColor = Surface1) {
            InfoContent(current, metas[current.key] ?: vm.repo.metaFor(current))
        }
    }
}

@Composable
fun ViewerPage(
    repo: ImageRepo,
    shot: Shot,
    raw: Boolean = false,
    onTap: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (up: Boolean) -> Unit,
) {
    var source by remember(shot.key, raw) { mutableStateOf<SubSamplingImageSource?>(null) }
    var failed by remember(shot.key, raw) { mutableStateOf(false) }
    LaunchedEffect(shot.key, raw) {
        val s = runCatching { if (raw) repo.rawSource(shot) else repo.viewerSource(shot) }.getOrNull()
        if (s == null) failed = true else source = s
    }
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        val src = source
        if (src == null) {
            val thumb = repo.peekPreview(shot) ?: repo.peekGrid(shot)
            if (thumb != null) {
                val img = remember(thumb) { thumb.asImageBitmap() }
                Image(img, shot.baseName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (failed) {
                Text(
                    if (raw) "Impossibile sviluppare questo RAW" else "Impossibile aprire questo file",
                    color = Color.White, modifier = Modifier.align(Alignment.Center),
                )
            } else if (raw) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sviluppo RAW…", color = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            } else if (thumb == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 3f))
            val state = rememberSubSamplingImageState(imageSource = src, zoomableState = zoom)
            var drag by remember { mutableFloatStateOf(0f) }
            val notZoomed = { (zoom.zoomFraction ?: 0f) < 0.02f }
            val swipeMod = if (swipeEnabled) {
                Modifier.pointerInput(shot.key) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragCancel = { drag = 0f },
                        onDragEnd = {
                            if (abs(drag) > threshold && notZoomed()) onSwipe(drag < 0)
                            drag = 0f
                        },
                    ) { change, dy ->
                        if (notZoomed()) {
                            drag += dy
                            change.consume()
                        }
                    }
                }
            } else Modifier
            SubSamplingImage(
                state = state,
                contentDescription = shot.baseName,
                modifier = Modifier
                    .fillMaxSize()
                    .then(swipeMod)
                    .zoomable(zoom, onClick = { onTap() }),
            )
            if (abs(drag) > threshold / 3) {
                val up = drag < 0
                val strong = abs(drag) > threshold
                Text(
                    if (up) "✓ Scarica" else "✗ Scarta",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .align(if (up) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(vertical = 140.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background((if (up) PickGreen else RejectRed).copy(alpha = if (strong) 1f else 0.5f))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}
