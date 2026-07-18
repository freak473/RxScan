package com.rxscan.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PaperCard
import com.rxscan.app.ui.theme.Amber
import com.rxscan.app.ui.theme.AmberBg
import com.rxscan.app.ui.theme.DisplayFamily
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.Green950
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.GreenTint
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.RxRed
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Today / home (design: scr-home). Doses grouped by their actual ring time
 * (derived from meals), a next-dose card, tap a dose for Taken / Snooze / Skip,
 * and an adherence summary. Footer: scan new + preview a reminder.
 */
private data class Dose(val id: String, val med: String, val sub: String, val time: String, val group: String)

private val doseGroups = listOf(
    Triple("7:30 AM", "before breakfast", listOf(Dose("d1", "Pantocid 40", "1 tablet · 30 min before breakfast", "7:30 AM", "g1"))),
    Triple("8:30 AM", "after breakfast", listOf(
        Dose("d2", "Augmentin 625", "1 tablet · after breakfast", "8:30 AM", "g2"),
        Dose("d3", "Ascoril LS", "2.5 ml · after breakfast", "8:30 AM", "g2"),
    )),
    Triple("2:00 PM", "after lunch", listOf(Dose("d4", "Ascoril LS", "2.5 ml · after lunch", "2:00 PM", "g3"))),
    Triple("9:00 PM", "after dinner", listOf(
        Dose("d5", "Augmentin 625", "1 tablet · after dinner", "9:00 PM", "g4"),
        Dose("d6", "Ascoril LS", "2.5 ml · after dinner", "9:00 PM", "g4"),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    notifAllowed: Boolean,
    onScanNew: () -> Unit,
    onPreviewReminder: () -> Unit,
    onOpenProgress: () -> Unit,
) {
    val context = LocalContext.current
    val status = remember {
        mutableStateMapOf(
            "d1" to "taken", "d2" to "taken", "d3" to "taken", "d4" to "taken",
            "d5" to "pending", "d6" to "pending",
        )
    }
    var sheetDose by remember { mutableStateOf<Dose?>(null) }

    val allDoses = doseGroups.flatMap { it.third }
    val taken = allDoses.count { status[it.id] == "taken" }
    val next = allDoses.firstOrNull { status[it.id] == "pending" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        // Head
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 14.dp, top = 22.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Saturday, 11 July", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Text("Today", fontFamily = DisplayFamily, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            }
            Icon(
                Icons.Outlined.MonitorHeart,
                contentDescription = "Open progress",
                tint = Green,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenProgress)
                    .padding(8.dp)
                    .size(26.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // Persistent silenced banner (PRD §6.4): denial still saves and schedules,
            // but reminders can't ring — deep-link to the app's notification settings.
            if (!notifAllowed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFF7E9E6))
                        .border(1.dp, RxRed.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .clickable {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = RxRed, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Reminders are silenced", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = RxRed)
                        Text(
                            "Your doses are saved. Tap to turn on notifications in Settings.",
                            fontSize = 12.sp, lineHeight = 16.sp, color = RxRed,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Next dose card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Green950)
                    .padding(16.dp),
            ) {
                if (next != null) {
                    Text(
                        "NEXT DOSE · ${next.time}",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                        color = Color(0xFF8FBBAC),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(next.med, fontFamily = DisplayFamily, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(next.sub, fontSize = 13.sp, color = Color(0xFFA7C6BA), maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("ALL DONE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Color(0xFF8FBBAC))
                    Spacer(Modifier.height(4.dp))
                    Text("Nothing more today", fontFamily = DisplayFamily, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = White)
                    Text("Next reminder tomorrow at 7:30 AM", fontSize = 13.sp, color = Color(0xFFA7C6BA))
                }
            }

            Spacer(Modifier.height(16.dp))

            doseGroups.forEach { (time, whenLabel, doses) ->
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(time, fontFamily = DisplayFamily, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(whenLabel, fontSize = 12.5.sp, color = Faint)
                }
                doses.forEach { dose ->
                    DoseRow(
                        dose = dose,
                        status = status[dose.id] ?: "pending",
                        onClick = { sheetDose = dose },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))
            }

            // Adherence summary
            PaperCard {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$taken of ${allDoses.size} taken", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(GreenSoft),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (allDoses.isEmpty()) 0f else taken / allDoses.size.toFloat())
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Green),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Foot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(1.dp, PaperLine)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FootGhost(text = "Scan new", icon = { Icon(Icons.Outlined.PhotoCamera, null, tint = Green, modifier = Modifier.size(18.dp)) }, modifier = Modifier.weight(1f), onClick = onScanNew)
            FootGhost(text = "Preview a reminder", icon = null, modifier = Modifier.weight(1f), onClick = onPreviewReminder)
        }
    }

    // Dose action sheet: Taken / Snooze 30m / Skip
    sheetDose?.let { dose ->
        ModalBottomSheet(
            onDismissRequest = { sheetDose = null },
            containerColor = White,
        ) {
            Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp)) {
                Text(dose.med, fontFamily = DisplayFamily, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text("${dose.sub} · ${dose.time}", fontSize = 13.5.sp, color = Muted)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Green)
                        .clickable {
                            status[dose.id] = "taken"; sheetDose = null
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Check, null, tint = White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Taken", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = White)
                }
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, PaperLine, RoundedCornerShape(14.dp))
                            .clickable { status[dose.id] = "snooze"; sheetDose = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Snooze 30m", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, RxRed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable { status[dose.id] = "skip"; sheetDose = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Skip", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RxRed)
                    }
                }
            }
        }
    }
}

@Composable
private fun DoseRow(dose: Dose, status: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(White)
            .border(1.dp, PaperLine, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(GreenTint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Medication, null, tint = Green, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(dose.med, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dose.sub, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusChip(status)
    }
}

@Composable
private fun StatusChip(status: String) {
    val (text, fg, bg) = when (status) {
        "taken" -> Triple("✓ Taken", Green, GreenSoft)
        "snooze" -> Triple("◷ Snoozed 30m", Amber, AmberBg)
        "skip" -> Triple("✕ Skipped", RxRed, Color(0xFFF7E9E6))
        else -> Triple("Due", Muted, PaperLine.copy(alpha = 0.5f))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
private fun FootGhost(text: String, icon: (@Composable () -> Unit)?, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, PaperLine, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { it(); Spacer(Modifier.width(7.dp)) }
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Green)
    }
}
