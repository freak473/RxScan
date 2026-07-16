package com.rxscan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.theme.Green950
import com.rxscan.app.ui.theme.Ink
import com.rxscan.app.ui.theme.InkFamily
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Reminder preview (design: scr-lockscreen). Discreet by default — a count,
 * never medicine names; names can be enabled in Settings. Grouped notification.
 */
@Composable
fun LockPreviewScreen(onOpen: () -> Unit, onSnooze: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B241E), Green950)))
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(70.dp))
        Text("9:00", fontSize = 64.sp, fontWeight = FontWeight.Light, color = White)
        Text("Saturday, 11 July", fontSize = 15.sp, color = Color(0xFFA7C6BA))

        Spacer(Modifier.height(36.dp))

        // The notification
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(White)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("℞", fontFamily = InkFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                Spacer(Modifier.width(6.dp))
                Text("RxScan", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.weight(1f))
                Text("now", fontSize = 12.sp, color = Muted)
            }
            Spacer(Modifier.height(8.dp))
            Text("Night medicines · 2 due", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Open to see them and check off.", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Row {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFEFF4F0))
                        .clickable(onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Open", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFEFF4F0))
                        .clickable(onClick = onSnooze),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Snooze 30m", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "Medicine names stay hidden on the lock screen.\nYou can show them in Settings.",
            fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFA7C6BA),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
        Text(
            "Reminders ring even without internet",
            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF7CC7B4),
        )
        Spacer(Modifier.height(30.dp))
    }
}
