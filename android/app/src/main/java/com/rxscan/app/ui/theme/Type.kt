package com.rxscan.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rxscan.app.R

// Bundled OFL faces (res/font): Bricolage Grotesque + Hanken Grotesk are variable
// fonts, so each weight is the same file pinned to a wght instance; Kalam ships as
// static Regular/Bold.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variableFont(res: Int, weight: FontWeight) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val DisplayFamily = FontFamily(
    variableFont(R.font.bricolage_grotesque, FontWeight.Bold),
    variableFont(R.font.bricolage_grotesque, FontWeight.ExtraBold),
)

val UiFamily = FontFamily(
    variableFont(R.font.hanken_grotesk, FontWeight.Normal),
    variableFont(R.font.hanken_grotesk, FontWeight.Medium),
    variableFont(R.font.hanken_grotesk, FontWeight.SemiBold),
    variableFont(R.font.hanken_grotesk, FontWeight.Bold),
    variableFont(R.font.hanken_grotesk, FontWeight.ExtraBold),
)

val InkFamily = FontFamily(
    Font(R.font.kalam_regular, FontWeight.Normal),
    Font(R.font.kalam_bold, FontWeight.Bold),
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = UiFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = UiFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = UiFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp, lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = UiFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = UiFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp
    ),
)
