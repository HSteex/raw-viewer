@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hsteex.rawviewer.ui

import android.os.SystemClock
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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.abs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.hsteex.rawviewer.data.ImageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.snap
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hsteex.rawviewer.data.PhotoMeta
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.mutableIntStateOf
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun ViewerScreen(vm: MainViewModel, screen: Screen.Viewer) {
    val shotMap by vm.shotByKey.collectAsStateWithLifecycle()
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    val metas by vm.metas.collectAsStateWithLifecycle()
    val autoAdvance by vm.prefs.autoAdvance.collectAsStateWithLifecycle()
    val swipe by vm.prefs.swipeGestures.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val showTimings by vm.prefs.showTimings.collectAsStateWithLifecycle()
    val histogram by vm.prefs.showHistogram.collectAsStateWithLifecycle()
    val clipping by vm.prefs.showClipping.collectAsStateWithLifecycle()
    val focus by vm.prefs.showFocus.collectAsStateWithLifecycle()
    var afRequest by remember { mutableIntStateOf(0) }
    var afKey by remember { mutableStateOf<String?>(null) }
    val keys = screen.keys
    val pager = rememberPagerState(initialPage = screen.start) { keys.size }
    var chrome by remember { mutableStateOf(true) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val currentKey = keys.getOrNull(pager.currentPage)
    val current = currentKey?.let { shotMap[it] }
    val rating = currentKey?.let { ratings[it] } ?: Rating()

    // Priorità: prima l'anteprima della foto a schermo, poi le anteprime delle vicine;
    // la piena risoluzione solo se ci si ferma sulla foto.
    val settled = pager.settledPage
    var readyKey by remember { mutableStateOf<String?>(null) }
    var warmKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settled, shotMap) {
        val k = keys.getOrNull(settled) ?: return@LaunchedEffect
        vm.lastViewedKey = k
        withTimeoutOrNull(NEIGHBOR_WAIT_MS) { snapshotFlow { readyKey }.first { it == k } }
        warmKey = k
        // le pagine ±1 caricano da sole la loro anteprima; qui si porta avanti la +2
        vm.repo.prefetch(listOfNotNull(keys.getOrNull(settled + 2)?.let(shotMap::get)))
    }
    val warm = warmKey != null && warmKey == keys.getOrNull(settled)

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

    // le barre sono vetro scuro: sfocano la foto che passa sotto senza cambiarne i colori
    val haze = remember { HazeState() }
    val glass = frostedDark()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { keys[it] },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        ) { page ->
            val s = shotMap[keys[page]]
            if (s != null) {
                ViewerPage(
                    repo = vm.repo, shot = s,
                    raw = showRaw && vm.repo.canDevelopRaw(s),
                    active = page == settled,
                    warm = warm,
                    showTimings = showTimings,
                    histogram = histogram,
                    clipping = clipping,
                    chrome = chrome,
                    focus = focus,
                    meta = metas[s.key] ?: vm.repo.metaFor(s),
                    afRequest = if (afKey == s.key) afRequest else 0,
                    onPreview = { vm.publishMeta(s); readyKey = s.key },
                    onTap = { chrome = !chrome },
                    swipeEnabled = swipe,
                    onSwipe = { up -> flag(s.key, if (up) Flag.PICK else Flag.REJECT) },
                )
            } else {
                CenterBox(Modifier.fillMaxSize()) { Text("File non più disponibile", color = Color.White) }
            }
        }

        // indicatore dello stato anche a interfaccia nascosta
        if (!chrome && (rating.flag != Flag.NONE || rating.downloaded)) {
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (rating.downloaded) DownloadedMarker()
                FlagMarker(rating.flag)
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .hazeEffect(haze, glass)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current?.baseName ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "${pager.currentPage + 1} / ${keys.size}" + (current?.let { "  ·  " + it.badge } ?: "") +
                            if (rating.downloaded) "  ·  ⬇ già scaricata" else "",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    )
                }
                if (canUndo) IconButton(onClick = { vm.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Annulla voto", tint = Color.White) }
                if (pager.currentPage < keys.lastIndex) {
                    IconButton(onClick = { currentKey?.let { vm.openCompare(it, keys[pager.currentPage + 1], keys) } }) {
                        Icon(Icons.Filled.Compare, "Confronta con la successiva", tint = Color.White)
                    }
                }
                IconButton(onClick = {
                    current?.let { s -> vm.shareIntent(listOf(s))?.let { runCatching { context.startActivity(it) } } }
                }) { Icon(Icons.Filled.Share, "Condividi", tint = Color.White) }
            }
        }

        // cassetto: da chiuso dati di scatto + dock ✗ · stelle · ✓; tirato su anche analisi, JPEG/RAW e info
        AnimatedVisibility(
            chrome,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val meta = current?.let { metas[it.key] ?: vm.repo.metaFor(it) }
            val line = meta?.let { m ->
                listOfNotNull(m.exposure, m.fNumber?.let { "f/%.1f".format(it) }, m.iso?.let { "ISO $it" }, m.film)
            }.orEmpty().joinToString("  ·  ")
            ViewerDrawer(
                expanded = drawerOpen,
                onExpandedChange = { drawerOpen = it },
                haze = haze, glass = glass, rating = rating, exposureLine = line,
                onReject = { currentKey?.let { flag(it, Flag.REJECT) } },
                onPick = { currentKey?.let { flag(it, Flag.PICK) } },
                onStar = { n -> currentKey?.let { stars(it, n) } },
                histogram = histogram, onHistogram = { vm.prefs.setShowHistogram(!histogram) },
                clipping = clipping, onClipping = { vm.prefs.setShowClipping(!clipping) },
                focus = focus, onFocus = { vm.prefs.setShowFocus(!focus) },
                hasAf = meta?.focusX != null,
                onAfZoom = { afKey = currentKey; afRequest++ },
                rawChoice = current?.takeIf { vm.repo.canDevelopRaw(it) }?.let { if (it.jpeg != null) "JPEG" else "Anteprima" },
                showRaw = showRaw, onRaw = { showRaw = it },
            ) {
                current?.let { InfoContent(it, meta) }
            }
        }
    }
}

private const val NEIGHBOR_WAIT_MS = 1500L
/** Se si resta sulla foto almeno questo tempo, parte la piena risoluzione (scorrendo veloce si leggono solo anteprime). */
private const val FULL_RES_DELAY_MS = 300L

/**
 * Una foto del visualizzatore.
 * [active]: è la pagina a schermo, quindi carica la piena risoluzione (le altre restano all'anteprima).
 * [warm]: la pagina a schermo è pronta, le vicine possono caricare la loro anteprima.
 */
@Composable
fun ViewerPage(
    repo: ImageRepo,
    shot: Shot,
    raw: Boolean = false,
    active: Boolean = true,
    warm: Boolean = true,
    onTap: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (up: Boolean) -> Unit,
    showTimings: Boolean = false,
    histogram: Boolean = false,
    clipping: Boolean = false,
    chrome: Boolean = true,
    /** Stato dello zoom passato da fuori (confronto con zoom collegato); null = proprio. */
    zoomState: ZoomableState? = null,
    focus: Boolean = false,
    meta: PhotoMeta? = null,
    /** Incrementato per chiedere lo zoom al 100% sul punto AF. */
    afRequest: Int = 0,
    onPreview: () -> Unit = {},
) {
    var source by remember(shot.key, raw) { mutableStateOf<SubSamplingImageSource?>(null) }
    var failed by remember(shot.key, raw) { mutableStateOf(false) }
    var preview by remember(shot.key) { mutableStateOf(repo.peekPreview(shot)) }
    val gridThumb = remember(shot.key) { repo.peekGrid(shot) }
    // diagnostica: tempi dall'arrivo sulla pagina
    var t0 by remember(shot.key, raw) { mutableStateOf(0L) }
    var tPreview by remember(shot.key, raw) { mutableStateOf<Long?>(null) }
    var tSource by remember(shot.key, raw) { mutableStateOf<Long?>(null) }
    var tFull by remember(shot.key, raw) { mutableStateOf<Long?>(null) }
    val hadPreview = remember(shot.key) { preview != null }
    // istogramma e bruciature dall'anteprima già in memoria (sempre il JPEG, anche col toggle RAW)
    val statsSrc = preview ?: gridThumb
    val stats by produceState<ImageStats?>(null, statsSrc, histogram && active) {
        if (statsSrc != null && histogram && active) {
            value = withContext(Dispatchers.Default) { runCatching { ImageStats.analyze(statsSrc) }.getOrNull() }
        }
    }
    val mask by produceState<ImageBitmap?>(null, statsSrc, clipping && active && !raw) {
        value = null
        if (statsSrc != null && clipping && active && !raw) {
            value = withContext(Dispatchers.Default) {
                runCatching { ImageStats.clippingMask(statsSrc).asImageBitmap() }.getOrNull()
            }
        }
    }
    val focusMask by produceState<ImageBitmap?>(null, statsSrc, focus && active && !raw) {
        value = null
        if (statsSrc != null && focus && active && !raw) {
            value = withContext(Dispatchers.Default) {
                runCatching { ImageStats.focusMask(statsSrc).asImageBitmap() }.getOrNull()
            }
        }
    }
    val af = remember(meta) { afFraction(meta) }
    // richieste già eseguite: tornando su una pagina non si rifà lo zoom
    var afHandled by remember(shot.key) { mutableIntStateOf(afRequest) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // doppio tocco = 100%: ingrandimento massimo pari ai pixel veri della foto (tra 2× e 12×)
    val maxZoom = remember(meta, statsSrc, boxSize) {
        val m = meta
        val p = statsSrc
        if (m?.width == null || m.height == null || p == null || boxSize.width == 0 || boxSize.height == 0) 3f
        else {
            val fullLong = max(m.width, m.height).toFloat()
            val fit = fitRect(p.width.toFloat(), p.height.toFloat(), boxSize)
            (fullLong / max(fit.width, fit.height)).coerceIn(2f, 12f)
        }
    }

    // pagina vicina: solo l'anteprima (~1600px), e solo dopo che quella a schermo è pronta
    LaunchedEffect(shot.key, active, warm) {
        if (!active && warm && preview == null) preview = runCatching { repo.preview(shot) }.getOrNull()
    }
    // pagina a schermo: anteprima subito, poi piena risoluzione. Se si scorre via prima
    // che finisca, l'effetto viene annullato e la banda passa alla nuova pagina.
    LaunchedEffect(shot.key, raw, active) {
        if (!active || source != null || failed) return@LaunchedEffect
        t0 = SystemClock.uptimeMillis()
        if (preview == null) preview = runCatching { repo.preview(shot) }.getOrNull()
        tPreview = SystemClock.uptimeMillis() - t0
        onPreview()
        if (preview != null) delay(FULL_RES_DELAY_MS)
        val s = runCatching { if (raw) repo.rawSource(shot) else repo.viewerSource(shot) }.getOrNull()
        tSource = SystemClock.uptimeMillis() - t0
        if (s == null) failed = true else source = s
    }
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }

    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        val src = source
        if (src == null) {
            val thumb = preview ?: gridThumb
            if (thumb != null) {
                val img = remember(thumb) { thumb.asImageBitmap() }
                Image(img, shot.baseName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                AnalysisOverlay(
                    fitRect(thumb.width.toFloat(), thumb.height.toFloat(), boxSize),
                    mask, focusMask, if (focus) af else null,
                )
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
            val zoom = zoomState ?: key(maxZoom) { rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxZoom)) }
            LaunchedEffect(zoom, afRequest) {
                val p = af ?: return@LaunchedEffect
                if (afRequest <= afHandled) return@LaunchedEffect
                afHandled = afRequest
                zoom.resetZoom(snap())
                val b = zoom.transformedContentBounds
                if (b.isEmpty) return@LaunchedEffect
                // zoomTo vuole lo zoom *finale* rispetto ai pixel della foto: 1f = 100%
                zoom.zoomTo(1f, centroid = Offset(b.left + p.x * b.width, b.top + p.y * b.height))
                val nb = zoom.transformedContentBounds
                val at = Offset(nb.left + p.x * nb.width, nb.top + p.y * nb.height)
                zoom.panBy(Offset(boxSize.width / 2f, boxSize.height / 2f) - at)
            }
            val state = rememberSubSamplingImageState(imageSource = src, zoomableState = zoom)
            // finché telephoto non ha disegnato niente resta sotto l'anteprima: niente fotogrammi neri
            if (!state.isImageDisplayed) {
                (preview ?: gridThumb)?.let { p ->
                    val img = remember(p) { p.asImageBitmap() }
                    Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            if (showTimings) {
                LaunchedEffect(state) {
                    snapshotFlow { state.isImageDisplayedInFullQuality }.first { it }
                    if (t0 > 0) tFull = SystemClock.uptimeMillis() - t0
                }
            }
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
            // le maschere seguono lo zoom: si disegnano nel rettangolo attuale della foto
            AnalysisOverlay(zoom.transformedContentBounds, mask, focusMask, if (focus) af else null)
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
        if (histogram && active) {
            stats?.let { st ->
                HistogramPanel(
                    st, raw,
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = if (chrome) 60.dp else 44.dp, end = 10.dp),
                )
            }
        }
        if (showTimings && t0 > 0) {
            val parts = listOfNotNull(
                tPreview?.let { (if (hadPreview) "anteprima già in memoria" else "anteprima $it ms") },
                tSource?.let { (if (raw) "sviluppo" else "file pronto") + " $it ms" },
                tFull?.let { "piena qualità $it ms" },
            )
            Text(
                parts.joinToString("  ·  "),
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 8.dp, bottom = 150.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Dock di voto: una capsula di vetro con ✗ · stelle · ✓. Il bottone del voto dato si riempie del suo
 * colore con un piccolo rimbalzo e il bordo della capsula si accende di verde o rosso,
 * così lo stato si legge con la coda dell'occhio mentre si scorrono le foto.
 */
@Composable
private fun RatingDock(
    rating: Rating,
    haze: HazeState,
    glass: HazeStyle,
    onReject: () -> Unit,
    onPick: () -> Unit,
    onStar: (Int) -> Unit,
) {
    val ring by animateColorAsState(
        when (rating.flag) {
            Flag.PICK -> PickGreen
            Flag.REJECT -> RejectRed
            Flag.NONE -> Color.White.copy(alpha = 0.14f)
        },
        label = "ring",
    )
    val shape = RoundedCornerShape(40.dp)
    Row(
        Modifier
            .clip(shape)
            .hazeEffect(haze, glass)
            .border(1.5.dp, ring, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VerdictButton(Icons.Filled.Close, "Scarta", rating.flag == Flag.REJECT, RejectRed, onReject)
        StarsRow(rating.stars, onStar = onStar, size = 36.dp)
        VerdictButton(Icons.Filled.Check, "Scarica", rating.flag == Flag.PICK, PickGreen, onPick)
    }
}

@Composable
private fun ViewerDrawer(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    haze: HazeState,
    glass: HazeStyle,
    rating: Rating,
    exposureLine: String,
    onReject: () -> Unit,
    onPick: () -> Unit,
    onStar: (Int) -> Unit,
    histogram: Boolean,
    onHistogram: () -> Unit,
    clipping: Boolean,
    onClipping: () -> Unit,
    focus: Boolean,
    onFocus: () -> Unit,
    hasAf: Boolean,
    onAfZoom: () -> Unit,
    /** Etichetta del lato "non RAW" del selettore, null se il RAW non è sviluppabile. */
    rawChoice: String?,
    showRaw: Boolean,
    onRaw: (Boolean) -> Unit,
    info: @Composable () -> Unit,
) {
    val ring by animateColorAsState(
        when (rating.flag) {
            Flag.PICK -> PickGreen
            Flag.REJECT -> RejectRed
            Flag.NONE -> Color.White.copy(alpha = 0.12f)
        },
        label = "ring",
    )
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    var drag by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { d -> drag += d }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .hazeEffect(haze, glass)
                .border(1.5.dp, ring, shape)
                // tirare su apre, tirare giù chiude (solo sul cassetto: sulla foto lo swipe vota)
                .draggable(
                    dragState, Orientation.Vertical,
                    onDragStarted = { drag = 0f },
                    onDragStopped = {
                        if (drag < -40f) onExpandedChange(true) else if (drag > 40f) onExpandedChange(false)
                    },
                )
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(width = 64.dp, height = 28.dp)
                    .clickable(onClickLabel = if (expanded) "Chiudi dettagli" else "Apri dettagli") { onExpandedChange(!expanded) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
            }
            // dati di scatto e, a portata di pollice, il selettore JPEG/RAW
            if (exposureLine.isNotEmpty() || rawChoice != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        exposureLine, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (rawChoice != null) {
                        Spacer(Modifier.width(8.dp))
                        Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).padding(3.dp)) {
                            for ((label, raw) in listOf(rawChoice to false, "RAW" to true)) {
                                val sel = showRaw == raw
                                Text(
                                    label,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else Color.White,
                                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (sel) Accent else Color.Transparent)
                                        .clickable { onRaw(raw) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VerdictButton(Icons.Filled.Close, "Scarta", rating.flag == Flag.REJECT, RejectRed, onReject)
                StarsRow(rating.stars, onStar = onStar, size = 36.dp)
                VerdictButton(Icons.Filled.Check, "Scarica", rating.flag == Flag.PICK, PickGreen, onPick)
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DrawerLabel("Analisi")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DrawerChip("Istogramma", Icons.Filled.BarChart, histogram, onHistogram)
                        DrawerChip("Bruciature", Icons.Filled.Exposure, clipping, onClipping)
                        DrawerChip("Messa a fuoco", Icons.Filled.CenterFocusStrong, focus, onFocus)
                        if (hasAf) {
                            AssistChip(
                                onClick = onAfZoom,
                                label = { Text("100% sul punto AF") },
                                leadingIcon = { Icon(Icons.Filled.ZoomIn, null, Modifier.size(18.dp)) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = Color.White, leadingIconContentColor = Accent),
                            )
                        }
                    }
                    DrawerLabel("Scatto")
                    Box(Modifier.padding(start = 0.dp)) { info() }
                }
            }
        }
    }
}

@Composable
private fun DrawerLabel(text: String) {
    Text(text, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DrawerChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(if (selected) Icons.Filled.Check else icon, null, Modifier.size(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            iconColor = Color.White.copy(alpha = 0.8f),
            selectedContainerColor = Accent.copy(alpha = 0.25f),
            selectedLabelColor = Accent,
            selectedLeadingIconColor = Accent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = Color.White.copy(alpha = 0.25f),
            selectedBorderColor = Accent,
        ),
    )
}

@Composable
private fun VerdictButton(icon: ImageVector, description: String, active: Boolean, color: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) color else color.copy(alpha = 0.16f), label = "bg")
    val fg by animateColorAsState(if (active) Color.Black else color, label = "fg")
    val scale by animateFloatAsState(
        if (active) 1f else 0.9f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    Box(
        Modifier
            .size(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = fg, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun CheckItem(label: String, icon: ImageVector, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = if (checked) Accent else LocalContentColor.current) },
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, null, tint = Accent) },
        onClick = onClick,
    )
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon, description,
            tint = if (on) Accent else Color.White,
            modifier = if (on) Modifier.clip(CircleShape).background(Accent.copy(alpha = 0.18f)).padding(4.dp) else Modifier,
        )
    }
}

/** ✓ / ✗ rotondi: piccoli, ma pieni del loro colore quando sono attivi. */
@Composable
private fun FlagCircle(icon: ImageVector, description: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (active) color else Color.White.copy(alpha = 0.12f))
            .border(1.5.dp, color.copy(alpha = if (active) 1f else 0.6f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) Color.Black else color, modifier = Modifier.size(26.dp))
    }
}

/** Rettangolo occupato da un'immagine w×h adattata (Fit) in [box]. */
private fun fitRect(w: Float, h: Float, box: IntSize): Rect {
    if (w <= 0f || h <= 0f || box.width == 0 || box.height == 0) return Rect.Zero
    val s = minOf(box.width / w, box.height / h)
    val dw = w * s
    val dh = h * s
    return Rect(Offset((box.width - dw) / 2, (box.height - dh) / 2), Size(dw, dh))
}

/** Punto AF in frazioni dell'immagine *come la si vede* (già ruotata). */
private fun afFraction(m: PhotoMeta?): Offset? {
    val x = m?.focusX ?: return null
    val y = m.focusY ?: return null
    val w = m.width ?: return null
    val h = m.height ?: return null
    if (x > w || y > h) return null
    val fx = x / w.toFloat()
    val fy = y / h.toFloat()
    return when (m.orientation) {
        0, 1 -> Offset(fx, fy)
        3 -> Offset(1 - fx, 1 - fy)
        6 -> Offset(1 - fy, fx)
        8 -> Offset(fy, 1 - fx)
        else -> null
    }
}

/** Maschere (bruciature, fuoco) e punto AF disegnati nel rettangolo [r] della foto, anche zoomata. */
@Composable
private fun AnalysisOverlay(r: Rect, clip: ImageBitmap?, focus: ImageBitmap?, af: Offset?) {
    if (r.isEmpty || (clip == null && focus == null && af == null)) return
    val afColor = Accent
    Canvas(Modifier.fillMaxSize()) {
        val dstOff = IntOffset(r.left.roundToInt(), r.top.roundToInt())
        val dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt())
        for (m in listOfNotNull(focus, clip)) {
            drawImage(m, IntOffset.Zero, IntSize(m.width, m.height), dstOff, dstSize, alpha = 0.9f, filterQuality = FilterQuality.Low)
        }
        if (af != null) {
            val c = Offset(r.left + af.x * r.width, r.top + af.y * r.height)
            val half = (minOf(r.width, r.height) * 0.04f).coerceIn(14.dp.toPx(), 60.dp.toPx())
            val stroke = Stroke(width = 2.dp.toPx())
            drawRect(Color.Black.copy(alpha = 0.5f), Offset(c.x - half, c.y - half), Size(half * 2, half * 2), style = Stroke(4.dp.toPx()))
            drawRect(afColor, Offset(c.x - half, c.y - half), Size(half * 2, half * 2), style = stroke)
        }
    }
}

/** Istogramma RGB + luminanza, con le percentuali di luci bruciate / ombre chiuse. */
@Composable
private fun HistogramPanel(st: ImageStats, raw: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(176.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(8.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            // scala con radice: i picchi enormi (cielo, neri) non schiacciano il resto
            val peak = maxOf(
                st.red.drop(1).dropLast(1).max(), st.green.drop(1).dropLast(1).max(),
                st.blue.drop(1).dropLast(1).max(), st.luma.drop(1).dropLast(1).max(), 1,
            ).let { kotlin.math.sqrt(it.toDouble()) }
            fun channel(bins: IntArray, color: Color) {
                val path = Path()
                val w = size.width
                val h = size.height
                path.moveTo(0f, h)
                for (i in 0..255) {
                    val v = (kotlin.math.sqrt(bins[i].toDouble()) / peak).coerceAtMost(1.0).toFloat()
                    path.lineTo(i / 255f * w, h - v * h)
                }
                path.lineTo(w, h)
                path.close()
                drawPath(path, color)
            }
            channel(st.luma, Color.White.copy(alpha = 0.25f))
            channel(st.red, Color(0xFFFF4040).copy(alpha = 0.45f))
            channel(st.green, Color(0xFF40E040).copy(alpha = 0.40f))
            channel(st.blue, Color(0xFF4080FF).copy(alpha = 0.45f))
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                "ombre " + pct(st.clippedLow), fontSize = 10.sp,
                color = if (st.clippedLow > 0.005f) Color(ImageStats.MASK_LOW) else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            Text(
                "luci " + pct(st.clippedHigh), fontSize = 10.sp,
                color = if (st.clippedHigh > 0.005f) Color(ImageStats.MASK_HIGH) else Color.White.copy(alpha = 0.7f),
            )
        }
        if (raw) Text("calcolato sul JPEG", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
    }
}

private fun pct(f: Float): String = when {
    f <= 0f -> "0%"
    f < 0.001f -> "<0,1%"
    else -> "%.1f%%".format(f * 100)
}
