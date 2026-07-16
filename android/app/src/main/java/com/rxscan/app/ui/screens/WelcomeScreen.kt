package com.rxscan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PrimaryButton
import com.rxscan.app.ui.theme.Amber
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenTint
import com.rxscan.app.ui.theme.Ink
import com.rxscan.app.ui.theme.InkFamily
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Welcome / landing (design: scr-welcome). The hero states the contract:
 * "we read, you check, then we remind." No sign-in here — the first ask is the
 * camera, not a phone number (PRD Q13: account at save).
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp),
        ) {
            Spacer(Modifier.height(14.dp))

            // Brand lockup
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(White)
                        .border(1.dp, PaperLine, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("℞", fontFamily = InkFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("RxScan", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary, letterSpacing = (-0.2).sp)
                    Text(
                        "PRESCRIPTION REMINDERS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp,
                        color = Muted,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Hero: the handwritten paper → the typed, checked schedule
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(White)
                    .border(1.dp, PaperLine, RoundedCornerShape(20.dp))
                    .padding(12.dp),
            ) {
                Text(
                    "DR. A. SHARMA · CITY CLINIC",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = Faint,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text("℞", fontFamily = InkFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Ink)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Augmentin 625 — 1-0-1 ×5d (a/f)\nPantocid 4? — 1-0-0 (b/f)\nAscoril LS — 1-1-1\nTab Dolo 650 — SOS",
                        fontFamily = InkFamily,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = Ink,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    tint = Green,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(20.dp),
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GreenTint)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    HeroRow(Green, "Augmentin 625 · Morning · Night")
                    HeroRow(Green, "Pantocid · Morning · before food")
                    HeroRow(Amber, "Ascoril LS · 3 times a day")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Scan your prescription.\nWe’ll remind you.",
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    append("Point the camera at the paper. We read it, ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                        append("you check every medicine")
                    }
                    append(", then reminders ring for each dose — until the last day.")
                },
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = Muted,
            )
            Spacer(Modifier.height(12.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp)) {
            PrimaryButton("Get started", onClick = onGetStarted)
            Spacer(Modifier.height(8.dp))
            Text(
                "Scan first — sign in only when you save your reminders · No name, no email",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HeroRow(dotColor: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}
