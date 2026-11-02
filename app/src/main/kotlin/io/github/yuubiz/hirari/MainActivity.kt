package io.github.yuubiz.hirari

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.yuubiz.hirari.core.ui.HirariTheme
import io.github.yuubiz.hirari.feature.library.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HirariTheme {
                HomeScreen()
            }
        }
    }
}
