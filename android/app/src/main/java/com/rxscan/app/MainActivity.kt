package com.rxscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rxscan.app.ui.RxScanNav
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.RxScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RxScanTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Paper)
                        .systemBarsPadding(),
                    color = Paper,
                ) {
                    RxScanNav()
                }
            }
        }
    }
}
