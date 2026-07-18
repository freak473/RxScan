package com.rxscan.app.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PaperCard
import com.rxscan.app.ui.theme.DisplayFamily
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.GreenTint
import com.rxscan.app.ui.theme.Ink
import com.rxscan.app.ui.theme.InkFamily
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.RxRed
import com.rxscan.app.ui.theme.RxRedLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Progress (design: scr-courseend). Courses auto-stop with a day-before notice.
 * Send-to-your-doctor goes device-to-doctor via the native share sheet — our
 * servers never touch it. Export and full delete stay one tap.
 */
private data class Course(val name: String, val sub: String, val days: List<String>)

private val courses = listOf(
    Course("Augmentin 625 Duo", "Morning · Night · after food", listOf("done", "done", "today", "up", "up")),
    Course("Pantocid 40", "Morning · before food", listOf("done", "done", "today", "up", "up")),
    Course("Ascoril LS", "Morning · Afternoon · Night", listOf("done", "miss", "today", "up", "up")),
)

@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 22.dp, top = 20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Today",
                tint = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(8.dp)
                    .size(24.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text("Your progress", fontFamily = DisplayFamily, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text("Day 3 of 5 · preview", fontSize = 14.sp, color = Muted)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(18.dp))

            // Course-end notice
            PaperCard {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(GreenTint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, null, tint = Green, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Courses end Wednesday, 15 July", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            "Reminders stop by themselves after the last dose. We’ll tell you the day before.",
                            fontSize = 13.sp, lineHeight = 19.sp, color = Muted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            courses.forEach { course ->
                PaperCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(course.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(course.sub, fontSize = 12.5.sp, color = Muted)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            course.days.forEachIndexed { i, st -> DayChip(i + 1, st) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // PRN log
            PaperCard {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dolo 650 · when needed", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("As written: ", fontSize = 12.5.sp, color = Muted)
                            Text("SOS", fontFamily = InkFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text(" · no scheduled reminders", fontSize = 12.5.sp, color = Muted)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, PaperLine, RoundedCornerShape(12.dp))
                            .clickable { }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Log a dose", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Foot: share / export / delete
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(1.dp, PaperLine)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Green)
                    .clickable {
                        val report = "RxScan adherence report — Day 3 of 5\n" +
                            "Augmentin 625 Duo: days 1–2 all taken\n" +
                            "Pantocid 40: days 1–2 all taken\n" +
                            "Ascoril LS: day 1 taken, day 2 one dose missed\n" +
                            "(Shared from my phone — RxScan servers never see this.)"
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(send, "Send to your doctor"))
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = White, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send to your doctor", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = White)
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.5.dp, PaperLine, RoundedCornerShape(13.dp))
                        .clickable { },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FileDownload, null, tint = Green, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Export", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Green)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.5.dp, RxRedLine, RoundedCornerShape(13.dp))
                        .clickable { },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = RxRed, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Delete all", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = RxRed)
                }
            }
        }
    }
}

@Composable
private fun DayChip(day: Int, state: String) {
    val bg: Color; val fg: Color; val borderColor: Color
    when (state) {
        "done" -> { bg = Green; fg = White; borderColor = Green }
        "miss" -> { bg = Color(0xFFF7E9E6); fg = RxRed; borderColor = RxRedLine }
        "today" -> { bg = White; fg = Green; borderColor = Green }
        else -> { bg = GreenSoft.copy(alpha = 0.6f); fg = Faint; borderColor = PaperLine }
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (state == "done") {
            Icon(Icons.Filled.Check, null, tint = fg, modifier = Modifier.size(16.dp))
        } else if (state == "miss") {
            Text("✕", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
        } else {
            Text("$day", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
        }
    }
}
