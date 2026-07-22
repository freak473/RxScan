package com.rxscan.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.rxscan.app.ui.theme.White
import java.io.File

/**
 * Capture (design: scr-capture, dark). A real in-app CameraX live preview sits
 * inside the branded viewfinder: the shutter captures directly (one tap, no
 * external camera app), the gallery icon beside it opens the Android photo
 * picker. Both hand the resulting image URI to [onCapture]. Design chrome
 * (top hint, corner brackets, steadiness hint, promise line) is preserved.
 */
@Composable
fun CaptureScreen(onCapture: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera runtime permission (separate from the app-level consent screen).
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // CameraX: PreviewView surface + a single ImageCapture use case, bound once
    // permission is granted.
    val previewView = remember { PreviewView(ctx) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            } catch (_: Exception) {
                // Binding can fail on devices/emulators without a usable camera;
                // the gallery path still works.
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    // Shutter → capture directly to a cache file, then hand the URI forward.
    fun capture() {
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "rx_capture.jpg")
        if (file.exists()) file.delete()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    onCapture(Uri.fromFile(file))
                }

                override fun onError(exception: ImageCaptureException) {
                    // Swallow for this UI pass; user can retry or use gallery.
                }
            },
        )
    }

    // Gallery → photo picker (no permission needed). Cancel → null → no-op.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onCapture) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B241E), Color(0xFF06130F))),
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

        // Frame: live camera preview (or a permission placeholder) + corner brackets
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(White.copy(alpha = 0.06f))
                        .clickable { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Tap to allow the camera\n— or upload from gallery below",
                        color = White.copy(alpha = 0.7f), fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                }
            }

            // Corner brackets overlay
            CornerBrackets()
        }

        Spacer(Modifier.height(14.dp))

        // Steadiness hint
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
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
            // Shutter → in-app capture (re-requests permission if not yet granted)
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .border(4.dp, White.copy(alpha = 0.45f), CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(White)
                    .clickable {
                        if (hasCameraPermission) {
                            capture()
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
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
