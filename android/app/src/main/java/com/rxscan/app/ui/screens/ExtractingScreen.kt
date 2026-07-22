package com.rxscan.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rxscan.app.ui.theme.ChipPaper
import com.rxscan.app.ui.theme.DisplayFamily
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.Ink
import com.rxscan.app.ui.theme.InkFamily
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * Extracting (design: scr-extracting). Honest progress — the steps name what
 * actually happens, including the drug-list check. Timing mirrors the design
 * (~3.9s total), then hands off to Verify.
 */
private val steps = listOf(
    "Reading the paper",
    "Finding your medicines",
    "Checking names against the drug list",
    "Preparing your check-list",
)

@Composable
fun ExtractingScreen(imageUri: Uri? = null, onDone: () -> Unit) {
    // step state: index of the step currently "doing"; done = everything before it
    var doing by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(900); doing = 1
        delay(850); doing = 2
        delay(850); doing = 3
        delay(800); doing = 4
        delay(450); onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        // The paper being read: the real captured/picked photo, or the mock
        // paper as a fallback (keeps @Preview and the null path working).
        if (imageUri != null) {
            AsyncImage(
                model = imageUri,
                contentDescription = "Your prescription photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChipPaper)
                    .border(1.dp, PaperLine, RoundedCornerShape(8.dp)),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChipPaper)
                    .border(1.dp, PaperLine, RoundedCornerShape(8.dp))
                    .padding(16.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        "Dr. A. Sharma · MBBS, MD",
                        fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        color = Color(0xFFB9AE8F), modifier = Modifier.weight(1f),
                    )
                    Text("11/07/26", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB9AE8F))
                }
                Text(
                    "Augmentin 625 — 1-0-1 ×5d (a/f)\nPantocid 4? — 1-0-0 (b/f)\nAscoril LS — 1-1-1\nTab Dolo 650 — SOS",
                    fontFamily = InkFamily, fontSize = 14.sp, lineHeight = 24.sp, color = Ink,
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        Text("Reading your prescription…", fontFamily = DisplayFamily, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("Usually takes a few seconds.", fontSize = 14.sp, color = Muted)
        Spacer(Modifier.height(24.dp))

        Column(modifier = Modifier.fillMaxWidth(0.9f)) {
            steps.forEachIndexed { i, label ->
                Row(
                    modifier = Modifier.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        i < doing -> Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Green),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                        i == doing -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = Green,
                            trackColor = GreenSoft,
                        )
                        else -> Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .border(2.dp, PaperLine, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (i == doing) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            i < doing -> TextPrimary
                            i == doing -> TextPrimary
                            else -> Faint
                        },
                    )
                }
            }
        }
    }
}
