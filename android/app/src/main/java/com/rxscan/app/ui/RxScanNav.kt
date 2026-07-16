package com.rxscan.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import com.rxscan.app.ui.screens.WelcomeScreen

// Canonical v1 flow (design prototype + PRD §6, Q13 account-at-save):
// welcome → consent → capture → extracting → verify → mealtimes
//         → signin → otp → notifperm → today (⇄ lock preview, ⇄ progress)
private object Routes {
    const val WELCOME = "welcome"
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

@Composable
fun RxScanNav() {
    val nav = rememberNavController()
    // Phone number hoisted here so signin → otp share it (UI pass; no backend).
    var phone by rememberSaveable { mutableStateOf("") }

    NavHost(navController = nav, startDestination = Routes.WELCOME) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onGetStarted = { nav.navigate(Routes.CONSENT) })
        }
        composable(Routes.CONSENT) {
            ConsentScreen(onContinue = { nav.navigate(Routes.CAPTURE) })
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(onCapture = { nav.navigate(Routes.EXTRACTING) })
        }
        composable(Routes.EXTRACTING) {
            ExtractingScreen(onDone = {
                nav.navigate(Routes.VERIFY) {
                    popUpTo(Routes.EXTRACTING) { inclusive = true }
                }
            })
        }
        composable(Routes.VERIFY) {
            VerifyScreen(onAllConfirmed = { nav.navigate(Routes.MEAL_TIMES) })
        }
        composable(Routes.MEAL_TIMES) {
            // "Set my reminders" = the save moment → deferred sign-in (Q13).
            MealTimesScreen(onSave = { nav.navigate(Routes.SIGNIN) })
        }
        composable(Routes.SIGNIN) {
            SignInScreen(
                onBack = { nav.popBackStack() },
                onSendCode = {
                    phone = it
                    nav.navigate(Routes.OTP)
                },
            )
        }
        composable(Routes.OTP) {
            OtpScreen(
                phone = phone,
                onBack = { nav.popBackStack() },
                onVerified = { nav.navigate(Routes.NOTIF_PERM) },
            )
        }
        composable(Routes.NOTIF_PERM) {
            NotifPermScreen(onResult = { _ ->
                // Allow or deny: everything is still saved (denial shows a banner
                // on Today once real permissions are wired).
                nav.navigate(Routes.TODAY) {
                    popUpTo(Routes.WELCOME) { inclusive = true }
                }
            })
        }
        composable(Routes.TODAY) {
            TodayScreen(
                onScanNew = { nav.navigate(Routes.CAPTURE) },
                onPreviewReminder = { nav.navigate(Routes.LOCK) },
                onOpenProgress = { nav.navigate(Routes.PROGRESS) },
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
