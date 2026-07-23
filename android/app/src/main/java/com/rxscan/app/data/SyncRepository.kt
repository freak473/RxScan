package com.rxscan.app.data

import android.content.Context
import com.google.gson.Gson
import com.rxscan.app.data.local.RxScanStore
import com.rxscan.app.data.net.ConsentDto
import com.rxscan.app.data.net.ConsentsPutRequestDto
import com.rxscan.app.data.net.MedsPayloadDto
import com.rxscan.app.data.net.Network
import com.rxscan.app.data.net.OtpVerifyRequestDto
import com.rxscan.app.data.net.PreferencesPayloadDto
import com.rxscan.app.data.net.PreferencesPutRequestDto
import com.rxscan.app.data.net.PrescriptionsPostRequestDto
import retrofit2.HttpException
import java.io.IOException

/** Outcome of the post-OTP sync chain — the nav layer routes on this, never on raw exceptions. */
sealed interface SyncOutcome {
    data class Success(val userCreated: Boolean) : SyncOutcome
    data object InvalidOtp : SyncOutcome
    data object AuthExpired : SyncOutcome
    data object Network : SyncOutcome
    data object Failure : SyncOutcome
}

/**
 * Owns the post-verify sync (spec "App-flow alignment"): OTP verify → store JWT →
 * push confirmed prescription(s) held in Room (pendingSync=true) → push meal-time
 * preferences held in DataStore → mark synced. Contract rule: any 401 clears the
 * stored token so the FE routes back to signin (no refresh tokens in v1).
 */
class SyncRepository(context: Context) {
    private val store = RxScanStore(context)
    private val prescriptions = PrescriptionRepository(context)
    private val gson = Gson()

    /**
     * Login-first flow (v2): OTP verify happens BEFORE consent/capture, so it no
     * longer bundles consents — [pushConsents] sends those separately once the user
     * has actually seen the consent screen post-login.
     */
    suspend fun verifyOtp(phone: String, otp: String): SyncOutcome {
        val response = try {
            Network.authApi.verifyOtp(OtpVerifyRequestDto(phone, otp, emptyList()))
        } catch (e: HttpException) {
            return if (e.code() == 401) SyncOutcome.InvalidOtp else SyncOutcome.Failure
        } catch (_: IOException) {
            return SyncOutcome.Network
        }

        store.saveToken(response.token)
        return SyncOutcome.Success(response.userCreated)
    }

    /** Consent screen, now post-login: PUTs process + retain_optin directly. */
    suspend fun pushConsents(process: Boolean, retainOptIn: Boolean, at: String): SyncOutcome = try {
        Network.meApi.putConsents(
            ConsentsPutRequestDto(
                listOf(
                    ConsentDto("process", process, at),
                    ConsentDto("retain_optin", retainOptIn, at),
                ),
            ),
        )
        SyncOutcome.Success(userCreated = false)
    } catch (e: HttpException) {
        if (e.code() == 401) {
            store.saveToken(null)
            SyncOutcome.AuthExpired
        } else {
            SyncOutcome.Failure
        }
    } catch (_: IOException) {
        SyncOutcome.Network
    }

    /**
     * After the notif-permission screen: push the notify consent, then flush
     * whatever's pending in Room/DataStore (confirmed meds + meal-time prefs).
     */
    suspend fun finalizeSync(notifyGranted: Boolean, at: String): SyncOutcome {
        try {
            Network.meApi.putConsents(ConsentsPutRequestDto(listOf(ConsentDto("notify", notifyGranted, at))))
        } catch (e: HttpException) {
            return if (e.code() == 401) {
                store.saveToken(null)
                SyncOutcome.AuthExpired
            } else {
                SyncOutcome.Failure
            }
        } catch (_: IOException) {
            return SyncOutcome.Network
        }

        return try {
            pushPendingPrescriptions()
            pushPreferences()
            SyncOutcome.Success(userCreated = false)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                store.saveToken(null)
                SyncOutcome.AuthExpired
            } else {
                SyncOutcome.Failure // token is already saved; a later retry re-pushes from Room/DataStore
            }
        } catch (_: IOException) {
            SyncOutcome.Network
        }
    }

    private suspend fun pushPendingPrescriptions() {
        prescriptions.pendingSync().forEach { entity ->
            val body = PrescriptionsPostRequestDto(gson.fromJson(entity.payloadJson, MedsPayloadDto::class.java))
            if (entity.rxId == null) {
                val res = Network.prescriptionApi.create(body)
                prescriptions.markSynced(entity.localId, res.rxId, res.updatedAt)
            } else {
                val res = Network.prescriptionApi.update(entity.rxId, body)
                prescriptions.markSynced(entity.localId, entity.rxId, res.updatedAt)
            }
        }
    }

    private suspend fun pushPreferences() {
        val json = store.loadMealTimesJson() ?: return
        val payload = gson.fromJson(json, PreferencesPayloadDto::class.java)
        Network.meApi.putPreferences(PreferencesPutRequestDto(payload))
    }
}
