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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PaperCard
import com.rxscan.app.ui.theme.DisplayFamily
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenTint
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Permissions (design: scr-notifperm). Shown immediately after OTP, with 30
 * reminders one tap away — the ask is legible. The system dialog is a
 * two-refusal resource, never spent cold at launch (PRD §6 step 4 / §8).
 * In this UI pass the Android dialog is mocked inline, exactly as the design does.
 */
@Composable
fun NotifPermScreen(onResult: (allowed: Boolean) -> Unit) {
    var exactOn by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("One permission for reminders", fontFamily = DisplayFamily, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("Android will ask next. Without it, your reminders can’t ring.", fontSize = 14.sp, color = Muted)

        Spacer(Modifier.height(18.dp))

        PaperCard {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(GreenTint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Green, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Notifications", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            "30 dose reminders are ready to schedule. This permission is what lets them appear.",
                            fontSize = 13.5.sp, lineHeight = 19.sp, color = Muted,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Mock Android system dialog (the real POST_NOTIFICATIONS ask, wired later)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFDFCF8))
                        .border(1.dp, Color(0xFFE3DDCE), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                ) {
                    Text(
                        buildAnnotatedString {
                            append("Allow ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("RxScan") }
                            append(" to send you notifications?")
                        },
                        fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    )
                    Spacer(Modifier.height(13.dp))
                    Row {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(Green)
                                .clickable { onResult(true) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Allow", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = White)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(White)
                                .border(1.5.dp, PaperLine, RoundedCornerShape(13.dp))
                                .clickable { onResult(false) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Don’t allow", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Exact-time alarms — the separate, skippable second step
        PaperCard {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(GreenTint),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Alarm, contentDescription = null, tint = Green, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Exact-time alarms", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(
                        "Optional · a Settings toggle so doses ring on the minute, even in battery saver. Skippable — reminders then arrive within a few minutes.",
                        fontSize = 12.5.sp, lineHeight = 18.sp, color = Muted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = exactOn,
                    onCheckedChange = { exactOn = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Green,
                        checkedThumbColor = White,
                        uncheckedTrackColor = PaperLine,
                        uncheckedThumbColor = White,
                        uncheckedBorderColor = PaperLine,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "If you deny notifications, everything is still saved — Today will show how to turn them on.",
            fontSize = 12.5.sp, lineHeight = 18.sp, color = Faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
