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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rxscan.app.ui.components.PaperCard
import com.rxscan.app.ui.components.PrimaryButton
import com.rxscan.app.ui.theme.Faint
import com.rxscan.app.ui.theme.Green
import com.rxscan.app.ui.theme.GreenSoft
import com.rxscan.app.ui.theme.Muted
import com.rxscan.app.ui.theme.Paper
import com.rxscan.app.ui.theme.TextPrimary
import com.rxscan.app.ui.theme.White

/**
 * Sign in — at save (design: scr-signin). Deferred deliberately (PRD Q13): the
 * user has already seen extraction work; the ask now has a visible purpose.
 * Phone is the only detail collected — no name, no email.
 */
@Composable
fun SignInScreen(onBack: () -> Unit, onSendCode: (String) -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    val valid = phone.length == 10

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
                Text("Save your reminders", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "So they’re safe if you change phones. Your number is the only detail we keep — no name, no email.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = Muted,
                )
            }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(24.dp))
            PaperCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Your mobile number",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Box(
                            modifier = Modifier
                                .height(58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(White)
                                .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFFDCD5C6), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🇮🇳 +91", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(White)
                                .border(
                                    1.5.dp,
                                    if (phone.isNotEmpty()) Green else androidx.compose.ui.graphics.Color(0xFFDCD5C6),
                                    RoundedCornerShape(16.dp),
                                )
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                value = phone,
                                onValueChange = { phone = it.filter(Char::isDigit).take(10) },
                                textStyle = TextStyle(
                                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp, color = TextPrimary,
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (phone.isEmpty()) {
                                Text("98765 43210", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Faint, letterSpacing = 0.5.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
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
                    "Your prescriptions are stored encrypted, and you can delete everything anytime.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = TextPrimary,
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
            PrimaryButton("Send code", onClick = { onSendCode(phone) }, enabled = valid)
        }
    }
}
