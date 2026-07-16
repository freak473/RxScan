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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PaperCard
import com.rxscan.app.ui.components.PrimaryButton
import com.rxscan.app.ui.theme.GreenTint
import com.rxscan.app.ui.theme.Ink
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Meal times (design: scr-mealtimes). AC/PC made concrete: before-food rings
 * 30 min before the meal, after-food 30 min after. Bedtime covers HS medicines.
 */
private data class MealSlot(val key: String, val label: String, val hint: String, val emoji: String, val default: Int)

private val slots = listOf(
    MealSlot("breakfast", "Breakfast", "Morning medicines", "🌤", 480),
    MealSlot("lunch", "Lunch", "Afternoon medicines", "☀️", 810),
    MealSlot("dinner", "Dinner", "Night medicines", "🌙", 1230),
    MealSlot("bedtime", "Bedtime", "For medicines marked HS — none today", "🛏", 1350),
)

private fun fmt(mins: Int): String {
    val h = mins / 60
    val m = mins % 60
    val ap = if (h >= 12) "PM" else "AM"
    val hh = (h % 12).let { if (it == 0) 12 else it }
    return "$hh:${m.toString().padStart(2, '0')} $ap"
}

@Composable
fun MealTimesScreen(onSave: () -> Unit) {
    var breakfast by rememberSaveable { mutableIntStateOf(480) }
    var lunch by rememberSaveable { mutableIntStateOf(810) }
    var dinner by rememberSaveable { mutableIntStateOf(1230) }
    var bedtime by rememberSaveable { mutableIntStateOf(1350) }

    fun value(key: String) = when (key) {
        "breakfast" -> breakfast; "lunch" -> lunch; "dinner" -> dinner; else -> bedtime
    }
    fun set(key: String, v: Int) {
        val c = v.coerceIn(300, 1410)
        when (key) {
            "breakfast" -> breakfast = c; "lunch" -> lunch = c; "dinner" -> dinner = c; else -> bedtime = c
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text("When do you eat?", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    append("Reminders follow your meals — ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) { append("before-food") }
                    append(" medicines ring 30 minutes before, ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) { append("after-food") }
                    append(" 30 minutes after.")
                },
                fontSize = 14.sp, lineHeight = 20.sp, color = Muted,
            )

            Spacer(Modifier.height(18.dp))
            slots.forEach { s ->
                PaperCard {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(GreenTint),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(s.emoji, fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(s.hint, fontSize = 11.5.sp, lineHeight = 15.sp, color = Muted)
                        }
                        Stepper("−") { set(s.key, value(s.key) - 15) }
                        Text(
                            fmt(value(s.key)),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink,
                            modifier = Modifier.width(74.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Stepper("+") { set(s.key, value(s.key) + 15) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Text(
                buildAnnotatedString {
                    append("Today that means: Pantocid at ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) { append(fmt(breakfast - 30)) }
                    append(" (before breakfast), Augmentin & Ascoril at ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) { append(fmt(breakfast + 30)) }
                    append(" (after breakfast). You can change these anytime.")
                },
                fontSize = 12.5.sp, lineHeight = 19.sp, color = Muted,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
            PrimaryButton("Set my reminders", onClick = onSave)
        }
    }
}

@Composable
private fun Stepper(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(White)
            .border(1.5.dp, PaperLine, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}
