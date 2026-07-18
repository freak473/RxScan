package com.rxscan.app.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PrimaryButton
import com.rxscan.app.ui.theme.DisplayFamily
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.RxRed
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White
import kotlinx.coroutines.delay

/**
 * OTP (design: scr-otp). One-time code verifies the number and creates the
 * account; on success the reminders schedule immediately — the ask-reward loop
 * is seconds long. Demo behavior per the design: any 6 digits verify; 000000
 * shows the error state.
 */
@Composable
fun OtpScreen(phone: String, onBack: () -> Unit, onVerified: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    var secondsLeft by rememberSaveable { mutableIntStateOf(30) }

    LaunchedEffect(secondsLeft > 0) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
    }

    val formatted = if (phone.length == 10) "+91 ${phone.take(5)} ${phone.drop(5)}" else "+91 $phone"

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
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(8.dp)
                    .size(24.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text("Enter the code", fontFamily = DisplayFamily, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildAnnotatedString {
                        append("Sent to ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) { append(formatted) }
                        append(" · ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Green)) { append("Change") }
                    },
                    fontSize = 14.sp, color = Muted,
                    modifier = Modifier.clickable(onClick = onBack),
                )
            }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(26.dp))

            // Six boxes backed by one invisible field (robust focus behavior)
            BasicTextField(
                value = code,
                onValueChange = {
                    code = it.filter(Char::isDigit).take(6)
                    error = false
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent),
                decorationBox = { inner ->
                    Box {
                        // invisible actual field (keeps IME + selection handling)
                        Box(modifier = Modifier.size(1.dp)) { inner() }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            repeat(6) { i ->
                                val ch = code.getOrNull(i)
                                val active = i == code.length
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(58.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(White)
                                        .border(
                                            width = if (active || ch != null || error) 2.dp else 1.5.dp,
                                            color = when {
                                                error -> RxRed
                                                ch != null -> Green
                                                active -> Green
                                                else -> androidx.compose.ui.graphics.Color(0xFFDCD5C6)
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        ch?.toString() ?: "",
                                        fontFamily = DisplayFamily, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                },
            )

            if (error) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "That code didn’t match. Check your messages and try again.",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RxRed,
                )
            }

            Spacer(Modifier.height(14.dp))
            if (secondsLeft > 0) {
                Text(
                    buildAnnotatedString {
                        append("Resend code in ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                            append("0:${secondsLeft.toString().padStart(2, '0')}")
                        }
                    },
                    fontSize = 13.5.sp, color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "Resend code",
                    fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Green,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            code = ""
                            error = false
                            secondsLeft = 30
                        }
                        .padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GreenSoft)
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "We keep only your number — no name, no email. Your prescriptions are stored encrypted, and you can delete everything anytime.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = TextPrimary,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Demo: any 6 digits verify · type 000000 to see the error state",
                fontSize = 12.sp, color = Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
            PrimaryButton(
                "Verify",
                onClick = {
                    if (code == "000000") error = true else onVerified()
                },
                enabled = code.length == 6,
            )
        }
    }
}
