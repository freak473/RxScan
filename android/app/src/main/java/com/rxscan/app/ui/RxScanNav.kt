package com.rxscan.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.gson.Gson
import com.rxscan.app.data.PrescriptionRepository
import com.rxscan.app.data.SyncOutcome
import com.rxscan.app.data.SyncRepository
import com.rxscan.app.data.local.RxScanStore
import com.rxscan.app.data.net.MealTimesDto
import com.rxscan.app.data.net.Network
import com.rxscan.app.data.net.OtpRequestDto
import com.rxscan.app.data.net.PreferencesPayloadDto
import com.rxscan.app.data.net.toMedsPayload
import com.rxscan.app.ui.screens.CaptureScreen
import com.rxscan.app.ui.screens.ConsentScreen
import com.rxscan.app.ui.screens.ExtractingScreen
import com.rxscan.app.ui.screens.LockPreviewScreen
import com.rxscan.app.ui.screens.MealTimesScreen
import com.rxscan.app.ui.screens.NotifPermScreen
import com.rxscan.app.ui.screens.OtpScreen
import com.rxscan.app.ui.screens.ProgressScreen
import com.rxscan.app.ui.screens.SignInScreen
import com.rxscan.app.ui.screens.TodayScreen
import com.rxscan.app.ui.screens.VerifyScreen
import com.rxscan.app.ui.theme.Paper
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

// Login-first v2 flow: a JWT already on-device skips straight to TODAY; otherwise
// the account is created up front, before any prescription is captured.
// signin → otp → consent → capture → extracting → verify → mealtimes → notifperm
//        → today (⇄ lock preview, ⇄ progress)
private object Routes {
    const val CONSENT = "consent"
    const val CAPTURE = "capture"
    const val EXTRACTING = "extracting"
    const val VERIFY = "verify"
    const val MEAL_TIMES = "meal_times"
    const val SIGNIN = "signin"
    const val OTP = "otp"
    const val NOTIF_PERM = "notif_perm"
    const val TODAY = "today"
    const val LOCK = "lock"
    const val PROGRESS = "progress"
}

/** Minutes-since-midnight (MealTimesScreen's unit) → the wire format "HH:mm". */
private fun minutesToHHmm(mins: Int): String = "%02d:%02d".format(mins / 60, mins % 60)

@Composable
fun RxScanNav() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RxScanStore(context) }
    val prescriptions = remember { PrescriptionRepository(context) }
    val sync = remember { SyncRepository(context) }
    val gson = remember { Gson() }

    // Phone number hoisted here so signin → otp share it.
    var phone by rememberSaveable { mutableStateOf("") }
    // Notification choice hoisted so Today can show the persistent silenced banner (PRD §6.4).
    var notifAllowed by rememberSaveable { mutableStateOf(true) }
    // Captured/picked prescription image, threaded capture → extracting. Plain remember:
    // a transient cache-file URI needn't survive process death for this UI pass.
    var capturedUri by remember { mutableStateOf<Uri?>(null) }
    // Real extracted medicines (from POST /extract), threaded extracting → verify → mealtimes.
    var meds by remember { mutableStateOf<List<com.rxscan.app.data.Medication>>(emptyList()) }

    // Persistent login: resolve the start destination from a stored JWT before the
    // NavHost is built at all (also hydrates the OkHttp interceptor's sync token cache).
    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startRoute = if (store.loadToken() != null) Routes.TODAY else Routes.SIGNIN
    }

    val resolvedStart = startRoute
    if (resolvedStart == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Paper),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(navController = nav, startDestination = resolvedStart) {
        composable(Routes.SIGNIN) {
            SignInScreen(
                onBack = {},
                onSendCode = {
                    phone = it
                    scope.launch {
                        store.savePhone(it)
                        runCatching { Network.authApi.requestOtp(OtpRequestDto(it)) }
                    }
                    nav.navigate(Routes.OTP)
                },
            )
        }
        composable(Routes.OTP) {
            OtpScreen(
                phone = phone,
                onBack = { nav.popBackStack() },
                onVerify = { code -> sync.verifyOtp(phone, code) is SyncOutcome.Success },
                onResend = {
                    scope.launch { runCatching { Network.authApi.requestOtp(OtpRequestDto(phone)) } }
                },
                onVerified = { nav.navigate(Routes.CONSENT) },
            )
        }
        composable(Routes.CONSENT) {
            ConsentScreen(onContinue = { process, retainOptIn ->
                scope.launch {
                    val outcome = sync.pushConsents(process, retainOptIn, OffsetDateTime.now().toString())
                    if (outcome is SyncOutcome.AuthExpired) {
                        nav.navigate(Routes.SIGNIN) { popUpTo(0) { inclusive = true } }
                    } else {
                        nav.navigate(Routes.CAPTURE)
                    }
                }
            })
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(onCapture = { uri ->
                capturedUri = uri
                nav.navigate(Routes.EXTRACTING)
            })
        }
        composable(Routes.EXTRACTING) {
            ExtractingScreen(
                imageUri = capturedUri,
                onExtracted = { extracted ->
                    meds = extracted
                    nav.navigate(Routes.VERIFY) {
                        popUpTo(Routes.EXTRACTING) { inclusive = true }
                    }
                },
                // Back to camera to retake/re-pick on an unrecoverable error.
                onBack = {
                    nav.navigate(Routes.CAPTURE) {
                        popUpTo(Routes.EXTRACTING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.VERIFY) {
            VerifyScreen(
                meds = meds,
                onAllConfirmed = { confirmedMeds ->
                    meds = confirmedMeds
                    // The save moment: confirmed meds go to Room now (pendingSync=true,
                    // rxId=null); the POST fires once notif-perm's finalizeSync runs.
                    val payload = confirmedMeds.toMedsPayload(OffsetDateTime.now().toString())
                    scope.launch { prescriptions.saveDraft(gson.toJson(payload)) }
                    nav.navigate(Routes.MEAL_TIMES)
                },
            )
        }
        composable(Routes.MEAL_TIMES) {
            MealTimesScreen(onSave = { breakfast, lunch, dinner ->
                val payload = PreferencesPayloadDto(
                    mealTimes = MealTimesDto(
                        breakfast = minutesToHHmm(breakfast),
                        lunch = minutesToHHmm(lunch),
                        dinner = minutesToHHmm(dinner),
                    ),
                )
                scope.launch { store.saveMealTimesJson(gson.toJson(payload)) }
                nav.navigate(Routes.NOTIF_PERM)
            })
        }
        composable(Routes.NOTIF_PERM) {
            NotifPermScreen(onResult = { allowed ->
                // Allow or deny: everything is still saved; denial shows the persistent
                // silenced banner on Today. The notify consent PUTs either way — a
                // denial IS the recorded choice.
                notifAllowed = allowed
                scope.launch {
                    val outcome = sync.finalizeSync(allowed, OffsetDateTime.now().toString())
                    if (outcome is SyncOutcome.AuthExpired) {
                        // Contract rule: any 401 ⇒ clear the token and route to signin.
                        nav.navigate(Routes.SIGNIN) { popUpTo(0) { inclusive = true } }
                    } else {
                        nav.navigate(Routes.TODAY) { popUpTo(0) { inclusive = true } }
                    }
                }
            })
        }
        composable(Routes.TODAY) {
            TodayScreen(
                notifAllowed = notifAllowed,
                onScanNew = { nav.navigate(Routes.CAPTURE) },
                onPreviewReminder = { nav.navigate(Routes.LOCK) },
                onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                onLogout = {
                    scope.launch {
                        store.saveToken(null)
                        nav.navigate(Routes.SIGNIN) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.LOCK) {
            LockPreviewScreen(
                onOpen = { nav.popBackStack() },
                onSnooze = { nav.popBackStack() },
            )
        }
        composable(Routes.PROGRESS) {
            ProgressScreen(onBack = { nav.popBackStack() })
        }
    }
}
