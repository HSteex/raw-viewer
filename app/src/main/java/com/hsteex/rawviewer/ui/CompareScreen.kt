package com.hsteex.rawviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hsteex.rawviewer.MainViewModel
import com.hsteex.rawviewer.Screen
import com.hsteex.rawviewer.data.Flag
import com.hsteex.rawviewer.data.Rating
import com.hsteex.rawviewer.data.Shot

/** Due scatti affiancati (in verticale col telefono dritto), ognuno con zoom indipendente. */
@Composable
fun CompareScreen(vm: MainViewModel, screen: Screen.Compare) {
    val shotMap by vm.shotByKey.collectAsStateWithLifecycle()
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    val a = shotMap[screen.a]
    val b = shotMap[screen.b]

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                listOfNotNull(a, b).forEach { s ->
                    ComparePane(vm, s, ratings[s.key] ?: Rating(), Modifier.weight(1f).fillMaxSize())
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                listOfNotNull(a, b).forEach { s ->
                    ComparePane(vm, s, ratings[s.key] ?: Rating(), Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        IconButton(
            onClick = { vm.back() },
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
    }
}

@Composable
private fun ComparePane(vm: MainViewModel, shot: Shot, rating: Rating, modifier: Modifier) {
    val borderColor = when (rating.flag) {
        Flag.PICK -> PickGreen
        Flag.REJECT -> RejectRed
        Flag.NONE -> Color.Transparent
    }
    Box(modifier.padding(2.dp).border(2.dp, borderColor)) {
        ViewerPage(repo = vm.repo, shot = shot, onTap = {}, swipeEnabled = false, onSwipe = {})
        Text(
            shot.baseName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            PickRejectButtons(
                rating.flag,
                onPick = { vm.toggleFlag(shot.key, Flag.PICK) },
                onReject = { vm.toggleFlag(shot.key, Flag.REJECT) },
            )
        }
    }
}
