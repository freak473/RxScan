package com.rxscan.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rxscan.app.data.net.TokenCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "rxscan_store")

/**
 * Plain class over Jetpack Preferences DataStore (spec "FE bindings + store"): JWT,
 * phone, meal times, and pre-login consents. FE holds these locally until login, then
 * uploads — accepted risk: a pre-login storage wipe loses nothing, since the server
 * holds no user data yet. Safe to construct more than once: the `by preferencesDataStore`
 * delegate is a process-wide singleton keyed by file name, regardless of which Context
 * instance's `.applicationContext` is used to reach it.
 */
class RxScanStore(context: Context) {
    private val ds = context.applicationContext.dataStore

    private object Keys {
        val JWT = stringPreferencesKey("jwt")
        val PHONE = stringPreferencesKey("phone")
        val MEAL_TIMES_JSON = stringPreferencesKey("meal_times_json")
        val CONSENTS_JSON = stringPreferencesKey("pending_consents_json")
    }

    /** Also mirrors into [TokenCache] — the OkHttp AuthInterceptor is synchronous. */
    suspend fun saveToken(token: String?) {
        ds.edit { p -> if (token == null) p.remove(Keys.JWT) else p[Keys.JWT] = token }
        TokenCache.current = token
    }

    suspend fun loadToken(): String? =
        ds.data.map { it[Keys.JWT] }.first().also { TokenCache.current = it }

    suspend fun savePhone(phone: String) { ds.edit { it[Keys.PHONE] = phone } }
    suspend fun loadPhone(): String? = ds.data.map { it[Keys.PHONE] }.first()

    suspend fun saveMealTimesJson(json: String) { ds.edit { it[Keys.MEAL_TIMES_JSON] = json } }
    suspend fun loadMealTimesJson(): String? = ds.data.map { it[Keys.MEAL_TIMES_JSON] }.first()

    suspend fun saveConsentsJson(json: String) { ds.edit { it[Keys.CONSENTS_JSON] = json } }
    suspend fun loadConsentsJson(): String? = ds.data.map { it[Keys.CONSENTS_JSON] }.first()
    suspend fun clearConsents() { ds.edit { it.remove(Keys.CONSENTS_JSON) } }
}
