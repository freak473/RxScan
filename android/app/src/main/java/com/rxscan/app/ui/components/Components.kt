package com.rxscan.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.theme.ChipPaper
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.Ink
import com.rxscan.app.ui.theme.InkFamily
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.White

/** Full-width primary action — brand green, generously rounded, tall tap target. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Green,
            contentColor = White,
            disabledContainerColor = Green.copy(alpha = 0.35f),
            disabledContentColor = White.copy(alpha = 0.7f),
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Secondary/ghost action — outlined, quieter than the primary. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PaperLine),
        modifier = modifier.height(48.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Green)
    }
}

/**
 * The "ink chip": a little scrap of paper showing the raw handwriting a value was
 * read from, in ballpoint-blue cursive. It's what lets a user check our reading
 * against what the doctor actually wrote.
 */
@Composable
fun InkChip(raw: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ChipPaper)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = raw,
            fontFamily = InkFamily,
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Ink,
        )
    }
}

/** A paper-pad card with the soft shadow / hairline border used across the app. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    borderColor: Color = PaperLine,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = White),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = { content() },
    )
}

/** Small solid status dot used inline in rows. */
@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** A horizontal pill row of the day's slots (Morning · Night). */
@Composable
fun SlotRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A single slot pill. */
@Composable
fun SlotPill(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Green.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Green)
    }
}

/** Consistent screen padding. */
val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
