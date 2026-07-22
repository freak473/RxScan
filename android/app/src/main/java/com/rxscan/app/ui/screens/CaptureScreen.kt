package com.rxscan.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rxscan.app.ui.theme.ChipPaper
import com.rxscan.app.ui.theme.Green950
import com.rxscan.app.ui.theme.Ink
import com.rxscan.app.ui.theme.InkFamily
import com.rxscan.app.ui.theme.White
import java.io.File

/**
 * Capture (design: scr-capture, dark). The branded viewfinder is now a launch
 * screen: the shutter opens the system camera (TakePicture), the gallery icon
 * beside it opens the Android photo picker (PickVisualMedia). Both hand the
 * resulting image URI to [onCapture]. No CameraX, no runtime permissions.
 * Copy and framing still match the design (top hint, brackets, steadiness hint).
 */
@Composable
fun CaptureScreen(onCapture: (Uri) -> Unit) {
    val ctx = LocalContext.current

    // Pre-created cache file + FileProvider URI the camera app writes the photo into.
    val cameraUri = remember {
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "rx_capture.jpg")
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    // System camera: on success the photo is at cameraUri. Cancel → success=false → no-op.
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) onCapture(cameraUri) }

    // Android photo picker: returns a content URI directly. Cancel → null → no-op.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onCapture) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B241E), Green950)),
            )
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(26.dp))

        // Top hint
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(White.copy(alpha = 0.10f))
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                "Fit the whole prescription in the frame",
                color = White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(22.dp))

        // Frame with corner brackets around the mock paper
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Corner brackets
            CornerBrackets()

            // The paper
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .rotate(-1.4f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChipPaper)
                    .padding(18.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Dr. A. Sharma · MBBS, MD",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        color = Color(0xFFB9AE8F), modifier = Modifier.weight(1f),
                    )
                    Text("11/07/26", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB9AE8F))
                }
                Text("℞", fontFamily = InkFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFFB9AE8F))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Augmentin 625 — 1-0-1 ×5d (a/f)\nPantocid 4? — 1-0-0 (b/f)\nAscoril LS — 1-1-1\nTab Dolo 650 — SOS",
                    fontFamily = InkFamily, fontSize = 15.sp, lineHeight = 26.sp, color = Ink,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Dr. Sharma",
                    fontFamily = InkFamily, fontSize = 14.sp, color = Ink.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Steadiness hint
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = Color(0xFF7CC7B4), fontSize = 11.sp)
            Spacer(Modifier.width(7.dp))
            Text("Good light · hold steady", color = White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Tap the shutter · you’ll check everything next",
            color = White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))

        // Shutter (centered) with the gallery icon to its left, camera-app style.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // Shutter → system camera
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .border(4.dp, White.copy(alpha = 0.45f), CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(White)
                    .clickable { cameraLauncher.launch(cameraUri) },
            )

            // Gallery → photo picker (far left, vertically centered on the shutter)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(White.copy(alpha = 0.12f))
                    .clickable {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = "Upload from gallery",
                    tint = White.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun CornerBrackets() {
    val c = White.copy(alpha = 0.55f)
    Box(modifier = Modifier.fillMaxWidth(0.94f).fillMaxSize()) {
        Corner(Modifier.align(Alignment.TopStart), top = true, start = true, color = c)
        Corner(Modifier.align(Alignment.TopEnd), top = true, start = false, color = c)
        Corner(Modifier.align(Alignment.BottomStart), top = false, start = true, color = c)
        Corner(Modifier.align(Alignment.BottomEnd), top = false, start = false, color = c)
    }
}

@Composable
private fun Corner(modifier: Modifier, top: Boolean, start: Boolean, color: Color) {
    Box(modifier = modifier.size(28.dp)) {
        // horizontal arm
        Box(
            modifier = Modifier
                .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        // vertical arm
        Box(
            modifier = Modifier
                .align(if (start) Alignment.CenterStart else Alignment.CenterEnd)
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}
