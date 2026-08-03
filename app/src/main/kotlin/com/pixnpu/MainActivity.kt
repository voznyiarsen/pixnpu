package com.pixnpu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pixnpu.ui.MainScreen
import com.pixnpu.ui.theme.PixNpuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixNpuTheme {
                MainScreen()
            }
        }
    }
}