package com.rxscan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PaperCard
import com.rxscan.app.ui.components.PrimaryButton
import com.rxscan.app.ui.theme.AmberBg
import com.rxscan.app.ui.theme.AmberLine
import com.rxscan.app.ui.theme.Amber
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.PaperLine
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Consent (design: scr-consent — screen 2). Purpose-specific, DPDP-shaped:
 * processing is required to scan; eval-set retention is a separate opt-in,
 * DEFAULT OFF; the under-18 toggle locks retention until a guardian confirms.
 */
@Composable
fun ConsentScreen(onContinue: () -> Unit) {
    var processOn by rememberSaveable { mutableStateOf(false) }
    var retainOn by rememberSaveable { mutableStateOf(false) }
    var minorOn by rememberSaveable { mutableStateOf(false) }

    // Minor ⇒ retention locked off (guardian must confirm first).
    val retainLocked = minorOn
    val effectiveRetain = retainOn && !retainLocked

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
            Spacer(Modifier.height(30.dp))
            Text("Before we start", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Two choices. You stay in control of both.", fontSize = 14.5.sp, color = Muted)

            Spacer(Modifier.height(20.dp))

            ConsentRow(
                title = "Read this prescription",
                body = "Needed to build your reminders. The photo is discarded right after reading.",
                tag = "Required to scan",
                checked = processOn,
                onCheckedChange = { processOn = it },
            )
            Spacer(Modifier.height(12.dp))
            ConsentRow(
                title = "Help improve accuracy",
                body = "Keep small clips of the text — never the whole prescription or your doctor’s details. Optional, off unless you turn it on.",
                checked = effectiveRetain,
                enabled = !retainLocked,
                onCheckedChange = { retainOn = it },
            )
            Spacer(Modifier.height(12.dp))
            ConsentRow(
                title = "This is for someone under 18",
                body = "A parent or guardian confirms before anything is saved.",
                checked = minorOn,
                onCheckedChange = { minorOn = it },
            )

            if (minorOn) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AmberBg)
                        .padding(12.dp),
                ) {
                    Text(
                        "Saving text clips is switched off for under-18 patients until a parent or guardian confirms in the next step.",
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = Amber,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            // Scribe note — the non-advisory contract, stated plainly.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GreenSoft)
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = Green, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "RxScan records what your doctor wrote. It does not give medical advice. When in doubt, ask your doctor or pharmacist.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = TextPrimary,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
            PrimaryButton(
                text = if (processOn) "Agree & continue" else "Turn on the first permission",
                onClick = onContinue,
                enabled = processOn,
            )
        }
    }
}

@Composable
private fun ConsentRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String? = null,
    enabled: Boolean = true,
) {
    PaperCard {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(3.dp))
                Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = Muted)
                if (tag != null) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GreenSoft)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(tag, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Green)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
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
}
