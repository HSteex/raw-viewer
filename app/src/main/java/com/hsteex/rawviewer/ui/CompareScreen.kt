package com.hsteex.rawviewer.ui

import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hsteex.rawviewer.MainViewModel
import com.hsteex.rawviewer.Screen
import com.hsteex.rawviewer.data.Flag
import com.hsteex.rawviewer.data.Rating
import com.hsteex.rawviewer.data.Shot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.math.abs

/**
 * Due scatti uno sopra l'altro (affiancati in orizzontale), con zoom collegato e una striscia
 * di foto in basso: un tocco su una miniatura la mette nel riquadro selezionato (di default il secondo),
 * così si tiene fermo il migliore e si scorrono le alternative (raffiche).
 */
@Composable
fun CompareScreen(vm: MainViewModel, screen: Screen.Compare) {
    val shotMap by vm.shotByKey.collectAsStateWithLifecycle()
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    var keyA by rememberSaveable { mutableStateOf(screen.a) }
    var keyB by rememberSaveable { mutableStateOf(screen.b) }
    var target by rememberSaveable { mutableIntStateOf(1) } // 0 = sopra/sinistra, 1 = sotto/destra
    var linked by rememberSaveable { mutableStateOf(true) }
    var paneSize by remember { mutableStateOf(IntSize.Zero) }

    val zoomA = key(keyA) { rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 3f)) }
    val zoomB = key(keyB) { rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 3f)) }
    LaunchedEffect(zoomA, zoomB, linked) {
        if (linked) linkZoom(zoomA, zoomB) { Size(paneSize.width.toFloat(), paneSize.height.toFloat()) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth > maxHeight
            val panes: @Composable (Modifier, Modifier) -> Unit = { m1, m2 ->
                shotMap[keyA]?.let { s ->
                    ComparePane(vm, s, ratings[s.key] ?: Rating(), zoomA, selected = target == 0, onSelect = { target = 0 },
                        modifier = m1.onSizeChanged { paneSize = it })
                }
                shotMap[keyB]?.let { s ->
                    ComparePane(vm, s, ratings[s.key] ?: Rating(), zoomB, selected = target == 1, onSelect = { target = 1 }, modifier = m2)
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) { panes(Modifier.weight(1f).fillMaxSize(), Modifier.weight(1f).fillMaxSize()) }
            } else {
                Column(Modifier.fillMaxSize()) { panes(Modifier.weight(1f).fillMaxWidth(), Modifier.weight(1f).fillMaxWidth()) }
            }
            Row(Modifier.align(Alignment.TopStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RoundButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
                RoundButton(onClick = { linked = !linked }) {
                    Icon(
                        if (linked) Icons.Filled.Link else Icons.Filled.LinkOff,
                        if (linked) "Zoom collegato" else "Zoom indipendente",
                        tint = if (linked) Accent else Color.White,
                    )
                }
                RoundButton(onClick = { val t = keyA; keyA = keyB; keyB = t }) {
                    Icon(Icons.Filled.SwapVert, "Scambia", tint = Color.White)
                }
            }
        }
        Filmstrip(vm, screen.keys, keyA, keyB, onPick = { k -> if (target == 0) keyA = k else keyB = k })
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) { content() }
}

@Composable
private fun ComparePane(
    vm: MainViewModel,
    shot: Shot,
    rating: Rating,
    zoom: ZoomableState,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    val borderColor = when (rating.flag) {
        Flag.PICK -> PickGreen
        Flag.REJECT -> RejectRed
        Flag.NONE -> if (selected) Accent.copy(alpha = 0.6f) else Color.Transparent
    }
    Box(modifier.padding(2.dp).border(2.dp, borderColor)) {
        ViewerPage(repo = vm.repo, shot = shot, onTap = onSelect, swipeEnabled = false, onSwipe = {}, zoomState = zoom)
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rating.downloaded) DownloadedMarker()
            Text(
                shot.baseName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.Black else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Accent else Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PickRejectButtons(
                rating.flag,
                onPick = { vm.toggleFlag(shot.key, Flag.PICK) },
                onReject = { vm.toggleFlag(shot.key, Flag.REJECT) },
                compact = true,
            )
            if (rating.stars > 0) {
                Spacer(Modifier.width(8.dp))
                Text("★".repeat(rating.stars), color = StarYellow, fontSize = 14.sp)
            }
        }
    }
}

/** Striscia di miniature: le due foto a confronto sono evidenziate, un tocco mette la foto nel riquadro selezionato. */
@Composable
private fun Filmstrip(vm: MainViewModel, keys: List<String>, keyA: String, keyB: String, onPick: (String) -> Unit) {
    val shotMap by vm.shotByKey.collectAsStateWithLifecycle()
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    val start = remember { (keys.indexOf(keyB).takeIf { it >= 0 } ?: 0) - 2 }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start.coerceAtLeast(0))
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(76.dp).background(Surface1),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(keys, key = { _, k -> k }) { _, k ->
            val s = shotMap[k] ?: return@itemsIndexed
            val r = ratings[k] ?: Rating()
            val mark = when (k) { keyA -> "1"; keyB -> "2"; else -> null }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface2)
                    .then(if (mark != null) Modifier.border(2.dp, Accent, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onPick(k) },
            ) {
                rememberGridThumb(vm.repo, s)?.let { bmp ->
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        img, s.baseName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        alpha = if (r.flag == Flag.REJECT) 0.4f else 1f,
                    )
                }
                FlagMarker(r.flag, Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp))
                if (mark != null) {
                    Text(
                        mark, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .background(Accent, RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Zoom collegato: quando uno dei due riquadri cambia (pinch, pan, doppio tocco) l'altro si porta
 * allo stesso ingrandimento relativo e sullo stesso punto della foto (in frazioni dell'immagine,
 * quindi funziona anche se le due foto hanno risoluzioni diverse).
 */
private suspend fun linkZoom(a: ZoomableState, b: ZoomableState, viewport: () -> Size) = coroutineScope {
    var applying: ZoomableState? = null

    fun fitWidth(r: Rect, vp: Size): Float {
        val ar = r.width / r.height
        return if (ar > vp.width / vp.height) vp.width else vp.height * ar
    }

    suspend fun mirror(from: ZoomableState, to: ZoomableState) {
        val vp = viewport()
        if (vp.width <= 0f || vp.height <= 0f) return
        val fb = from.transformedContentBounds
        val tb = to.transformedContentBounds
        if (fb.isEmpty || tb.isEmpty) return
        val zoomFrom = fb.width / fitWidth(fb, vp)
        val zoomTo = tb.width / fitWidth(tb, vp)
        val fx = (vp.width / 2 - fb.left) / fb.width
        val fy = (vp.height / 2 - fb.top) / fb.height
        applying = to
        try {
            if (abs(zoomFrom - zoomTo) / zoomFrom > 0.003f) to.zoomTo(zoomFrom, animationSpec = snap())
            val nb = to.transformedContentBounds
            val want = Offset(vp.width / 2 - fx * nb.width, vp.height / 2 - fy * nb.height)
            val d = want - nb.topLeft
            if (abs(d.x) > 0.5f || abs(d.y) > 0.5f) to.panBy(d, animationSpec = snap())
        } finally {
            applying = null
        }
    }

    // quando un riquadro ha appena caricato la sua foto è lui ad allinearsi all'altro, non viceversa
    fun follow(me: ZoomableState, other: ZoomableState) = launch {
        var was = Rect.Zero
        snapshotFlow { me.transformedContentBounds }.collectLatest { r ->
            val justLoaded = was.isEmpty && !r.isEmpty
            was = r
            if (applying === me) return@collectLatest
            if (justLoaded) mirror(other, me) else mirror(me, other)
        }
    }
    follow(a, b)
    follow(b, a)
}
