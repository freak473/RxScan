package com.rxscan.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rxscan.app.data.Medication
import com.rxscan.app.ui.ExtractError
import com.rxscan.app.ui.ExtractionState
import com.rxscan.app.ui.ExtractionViewModel
import com.rxscan.app.ui.theme.ChipPaper
import com.rxscan.app.ui.theme.DisplayFamily
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White
import kotlinx.coroutines.delay

/**
 * Extracting (design: scr-extracting). Now backed by the real POST /extract call
 * (via [ExtractionViewModel]): the honest step list plays while the request is in
 * flight, then hands the parsed medicines to Verify. On failure it shows a
 * cause-specific error with Retry — never fake data.
 */
private val steps = listOf(
    "Reading the paper",
    "Finding your medicines",
    "Checking names against the drug list",
    "Preparing your check-list",
)

@Composable
fun ExtractingScreen(
    imageUri: Uri?,
    onExtracted: (List<Medication>) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ExtractionViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(imageUri) {
        if (imageUri != null) vm.run(imageUri)
    }

    // Success is a navigation event, not a rendered state.
    LaunchedEffect(state) {
        val s = state
        if (s is ExtractionState.Success) onExtracted(s.meds)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val s = state) {
            is ExtractionState.Error -> ErrorPanel(
                cause = s.cause,
                onRetry = { if (imageUri != null) vm.run(imageUri) },
                onBack = onBack,
            )
            else -> LoadingBody(imageUri) // Loading (and the brief pre-nav Success)
        }
    }
}

@Composable
private fun LoadingBody(imageUri: Uri?) {
    // Indeterminate: advance through the steps and hold on the last until the call returns.
    var doing by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(700); doing = 1
        delay(700); doing = 2
        delay(700); doing = 3
    }

    // The photo being read (real capture/pick), or the mock paper as a fallback.
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
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(ChipPaper)
                .border(1.dp, PaperLine, RoundedCornerShape(8.dp)),
        )
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
                        modifier = Modifier.size(22.dp).clip(CircleShape).background(Green),
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
                        modifier = Modifier.size(22.dp).clip(CircleShape).border(2.dp, PaperLine, CircleShape),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (i == doing) FontWeight.Bold else FontWeight.Medium,
                    color = if (i <= doing) TextPrimary else Faint,
                )
            }
        }
    }
}

@Composable
private fun ErrorPanel(cause: ExtractError, onRetry: () -> Unit, onBack: () -> Unit) {
    val (title, body) = when (cause) {
        ExtractError.VISION_UNAVAILABLE ->
            "The reader isn’t switched on yet" to "The service is running but its prescription reader isn’t configured. Try again once it’s ready."
        ExtractError.NETWORK ->
            "Couldn’t reach the reader" to "We couldn’t connect. Check that the backend is running, then try again."
        ExtractError.BAD_IMAGE ->
            "That photo didn’t work" to "The image couldn’t be read. Go back and take or pick another."
        ExtractError.EMPTY ->
            "We couldn’t read any medicines" to "Nothing legible was found. A clearer, well-lit photo usually helps."
        ExtractError.UNKNOWN ->
            "Something went wrong" to "We couldn’t read this prescription. Please try again."
    }

    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Muted, modifier = Modifier.size(46.dp))
    Spacer(Modifier.height(16.dp))
    Text(title, fontFamily = DisplayFamily, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = Muted, textAlign = TextAlign.Center)
    Spacer(Modifier.height(26.dp))

    // Retry (primary)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Green)
            .clickable(onClick = onRetry),
        contentAlignment = Alignment.Center,
    ) {
        Text("Try again", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
    // Back to camera (secondary)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PaperLine, RoundedCornerShape(14.dp))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Text("Back to camera", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
