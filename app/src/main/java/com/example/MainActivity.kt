package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AdvancedSettingsScreen
import com.example.ui.HomeScreen
import com.example.ui.SiamVoiceViewModel
import com.example.ui.theme.SiamVoiceTheme

enum class Screen {
    HOME,
    ADVANCED_SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SiamVoiceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SiamVoiceApp()
                }
            }
        }
    }
}

@Composable
fun SiamVoiceApp(viewModel: SiamVoiceViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    when (currentScreen) {
        Screen.HOME -> {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSettings = { currentScreen = Screen.ADVANCED_SETTINGS }
            )
        }
        Screen.ADVANCED_SETTINGS -> {
            AdvancedSettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { currentScreen = Screen.HOME }
            )
        }
    }
}
