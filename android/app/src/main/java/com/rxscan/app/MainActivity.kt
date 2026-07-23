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
import androidx.lifecycle.lifecycleScope
import com.rxscan.app.reminders.ReminderScheduler
import com.rxscan.app.ui.RxScanNav
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.RxScanTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { ReminderScheduler.reschedule(this@MainActivity) }
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
