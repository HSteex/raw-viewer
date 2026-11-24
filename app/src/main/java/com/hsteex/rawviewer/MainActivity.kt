package com.hsteex.rawviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hsteex.rawviewer.ui.BrowserScreen
import com.hsteex.rawviewer.ui.CompareScreen
import com.hsteex.rawviewer.ui.HomeScreen
import com.hsteex.rawviewer.ui.RawTheme
import com.hsteex.rawviewer.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RawTheme {
                AppRoot(viewModel())
            }
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    BackHandler(enabled = screen != Screen.Home) { vm.back() }
    when (val s = screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Browser -> BrowserScreen(vm)
        is Screen.Viewer -> ViewerScreen(vm, s)
        is Screen.Compare -> CompareScreen(vm, s)
    }
}
