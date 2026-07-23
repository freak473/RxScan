# Consumer API v1 — FE Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the RxScan Android app to the consumer API v1 backend
(`docs/superpowers/specs/2026-07-23-consumer-api-v1-design.md`, `docs/api-contract-v1.md`):
phone-OTP sign-in, pre-login consents, meal-time preferences, and confirmed-prescription sync —
replacing the mocked signin/otp/notifperm flow with real HTTP calls, backed by a new DataStore
store and a Room cache.

**Architecture:** Retrofit + Gson (existing idiom — see `ExtractionApi`) for `AuthApi` / `MeApi` /
`PrescriptionApi`, behind one shared OkHttp client with a bearer `AuthInterceptor`. A plain
`RxScanStore` class over Jetpack Preferences DataStore holds pre-login state (JWT, phone, meal
times, pending consents). A one-table Room cache (`PrescriptionEntity`) is the disposable local
record of confirmed meds — `pendingSync=true` / `rxId=null` until the post-login `POST` assigns a
server id. A plain `SyncRepository` class orchestrates the post-OTP push (mirrors
`ExtractionRepository`'s shape). No DI framework; no new ViewModel (the only one that exists,
`ExtractionViewModel`, is untouched) — everything else is a plain class or the existing `Network`
object idiom.

**Tech Stack:** Kotlin 2.0.21 · AGP 8.7.3 · compileSdk/targetSdk 36 · minSdk 26 · Retrofit 2.11.0 +
Gson (existing, unchanged) · Jetpack Preferences DataStore 1.1.1 (new) · Room 2.6.1 + KSP
2.0.21-1.0.28 (new) · JUnit4 4.13.2 (new, DTO/mapper unit tests only).

## Global Constraints

- **CDSCO — no `suggested_value` field anywhere in any DTO or store; flag inputs stay EMPTY.**
  None of the DTOs or the Room/DataStore schema in this plan carry one; the meds payload's
  `strength`/`durationDays`/etc. are always what the user confirmed on Verify, never a system
  guess.
- **Base URL `http://10.0.2.2:8080/` (emulator→host).** Already `ApiConfig.BASE_URL` in
  `Network.kt` — unchanged, reused for the new APIs.
- **JSON wire format snake_case via Gson `@SerializedName`.** Applies only to the backend's own
  envelope fields (`rx_id`, `updated_at`, `user_created`, `granted_at`); the FE-owned opaque
  payload bodies (`meds`, `mealTimes`, `mealTiming`, `durationDays`, `confirmedAt`) are camelCase
  by contract (`docs/api-contract-v1.md`) and need no `@SerializedName` — do not add a global Gson
  `FieldNamingPolicy`.
- **Any `401` ⇒ clear stored JWT + route to signin (no refresh tokens).** Implemented once, in
  `SyncRepository`: every Bearer-authed call catches `HttpException` with `code()==401`, clears
  `RxScanStore`'s token (and `TokenCache`), and returns `SyncOutcome.AuthExpired` for the nav layer
  to route on.
- **Stub OTP is `000000`.** The backend's dev default (`rxscan.auth.dev-otp`) — `OtpScreen`'s old
  "any 6 digits verify / 000000 shows an error" demo branch is removed; `000000` is now the code
  that actually succeeds against a locally running backend.
- **No automated tests may hit a live backend or AI API** — unit-test DTO mapping/serialization
  with plain JUnit (Task 2's `PayloadMapperTest`, pure Kotlin, no Android framework needed);
  end-to-end verification is MANUAL via emulator against the locally running backend (Task 7).
- **Build command:** `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android assembleDebug`
  (gradlew works for Android; it's the backend `mvnw` that's broken — use system `mvn` for the
  backend, per the slice-a plan).
- **Repo root** `/Users/ankitjain/rxscan`; work happens on the already-checked-out branch
  **`docs/checklist`** — no branch switch needed, just commit there.
- **CLAUDE.md update-after-commit rule:** Task 1 appends a small "FE wiring in progress" note to
  CLAUDE.md's Current status and leaves it **uncommitted** through Tasks 2–6 (satisfies the
  post-commit hook as a pending edit); Task 7 finalizes and commits it — same pattern the backend
  slice-a plan used.
- **No new Gson/coroutines dependency:** `com.google.gson.Gson`/`@SerializedName` and
  `kotlinx.coroutines.launch`/`delay` are already on the compile classpath transitively (via
  `retrofit-converter-gson` and `androidx-lifecycle-runtime-ktx`) and already used in this codebase
  (`OtpScreen.kt` already imports `kotlinx.coroutines.delay`) — nothing new to add for those.

## File Structure

```
android/gradle/libs.versions.toml                                          (modify: Room/DataStore/KSP/JUnit)
android/build.gradle.kts                                                    (modify: ksp plugin apply false)
android/app/build.gradle.kts                                                 (modify: apply ksp + new deps)
android/app/src/main/java/com/rxscan/app/data/net/
  AuthDtos.kt                (create)
  MeDtos.kt                  (create)
  PrescriptionDtos.kt        (create)
  ApiError.kt                (create)
  AuthApi.kt                 (create)
  MeApi.kt                   (create)
  PrescriptionApi.kt         (create)
  AuthInterceptor.kt         (create — also holds TokenCache)
  PayloadMapper.kt           (create)
  Network.kt                 (modify: consumer Retrofit + 3 new APIs)
android/app/src/test/java/com/rxscan/app/data/net/
  PayloadMapperTest.kt       (create)
android/app/src/main/java/com/rxscan/app/data/local/
  RxScanStore.kt             (create)
  PrescriptionEntity.kt      (create)
  PrescriptionDao.kt         (create)
  RxScanDatabase.kt          (create)
android/app/src/main/java/com/rxscan/app/data/
  PrescriptionRepository.kt  (create)
  SyncRepository.kt          (create)
android/app/src/main/java/com/rxscan/app/ui/screens/
  ConsentScreen.kt           (modify: onContinue signature)
  VerifyScreen.kt            (modify: onAllConfirmed signature)
  MealTimesScreen.kt         (modify: onSave signature)
  OtpScreen.kt                (modify: real onVerify/onResend, demo branch removed)
android/app/src/main/java/com/rxscan/app/ui/
  RxScanNav.kt                (modify: real wiring for consent/verify/mealtimes/signin/otp/notifperm)
CLAUDE.md                     (modify: Current status, Task 1 pending → Task 7 commits)
```

---

### Task 1: Gradle — Room + DataStore + KSP

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/build.gradle.kts`
- Modify: `android/app/build.gradle.kts`
- Modify: `/Users/ankitjain/rxscan/CLAUDE.md` (pending edit only — do not commit)

**Interfaces:** none (dependency wiring only — no Kotlin symbols produced yet).

- [ ] **Step 1: Add versions, libraries, and the KSP plugin to the catalog**

  In `android/gradle/libs.versions.toml`, under `[versions]`, add (after `lifecycleViewmodelCompose`):

  ```toml
  room = "2.6.1"
  datastorePreferences = "1.1.1"
  ksp = "2.0.21-1.0.28"
  junit = "4.13.2"
  ```

  Under `[libraries]`, add (after `okhttp-logging-interceptor`):

  ```toml
  androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
  androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
  androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastorePreferences" }
  junit = { group = "junit", name = "junit", version.ref = "junit" }
  ```

  Under `[plugins]`, add:

  ```toml
  ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
  ```

- [ ] **Step 2: Apply the KSP plugin at the root (apply false, like the other plugins)**

  In `android/build.gradle.kts`, replace:

  ```kotlin
  plugins {
      alias(libs.plugins.android.application) apply false
      alias(libs.plugins.kotlin.android) apply false
      alias(libs.plugins.kotlin.compose) apply false
  }
  ```

  With:

  ```kotlin
  plugins {
      alias(libs.plugins.android.application) apply false
      alias(libs.plugins.kotlin.android) apply false
      alias(libs.plugins.kotlin.compose) apply false
      alias(libs.plugins.ksp) apply false
  }
  ```

- [ ] **Step 3: Apply KSP for real in the app module + add the new dependencies**

  In `android/app/build.gradle.kts`, replace:

  ```kotlin
  plugins {
      alias(libs.plugins.android.application)
      alias(libs.plugins.kotlin.android)
      alias(libs.plugins.kotlin.compose)
  }
  ```

  With:

  ```kotlin
  plugins {
      alias(libs.plugins.android.application)
      alias(libs.plugins.kotlin.android)
      alias(libs.plugins.kotlin.compose)
      alias(libs.plugins.ksp)
  }
  ```

  And replace the `dependencies { ... }` block's closing lines:

  ```kotlin
      implementation(libs.retrofit)
      implementation(libs.retrofit.converter.gson)
      implementation(libs.okhttp.logging.interceptor)
      debugImplementation(libs.androidx.ui.tooling)
  }
  ```

  With:

  ```kotlin
      implementation(libs.retrofit)
      implementation(libs.retrofit.converter.gson)
      implementation(libs.okhttp.logging.interceptor)
      implementation(libs.androidx.room.runtime)
      ksp(libs.androidx.room.compiler)
      implementation(libs.androidx.datastore.preferences)
      testImplementation(libs.junit)
      debugImplementation(libs.androidx.ui.tooling)
  }
  ```

- [ ] **Step 4: Verify the build still compiles with the new deps (no new code uses them yet)**

  ```bash
  JAVA_HOME=/usr/local/opt/openjdk@21 /Users/ankitjain/rxscan/android/gradlew -p /Users/ankitjain/rxscan/android assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL` (Gradle syncs and downloads Room/DataStore/KSP/JUnit; nothing
  references them yet so there's nothing new to compile).

- [ ] **Step 5: CLAUDE.md pending note (do NOT commit yet)**

  In CLAUDE.md's "Current status" paragraph for Phase 2, append one line:
  `FE wiring to the consumer API in progress (docs/superpowers/plans/2026-07-23-consumer-api-v1-fe-wiring.md).`
  Leave this uncommitted — Task 7 finalizes and commits CLAUDE.md.

- [ ] **Step 6: Commit**

  ```bash
  cd /Users/ankitjain/rxscan && git add android/gradle/libs.versions.toml android/build.gradle.kts android/app/build.gradle.kts
  git commit -m "android: add Room + DataStore + KSP to the gradle build"
  ```

---

### Task 2: Consumer API DTOs + AuthApi/MeApi/PrescriptionApi + auth interceptor + Network wiring

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/data/net/AuthDtos.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/net/MeDtos.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/net/PrescriptionDtos.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/net/ApiError.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/net/AuthApi.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/net/MeApi.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/net/PrescriptionApi.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/net/AuthInterceptor.kt` (also defines `TokenCache`)
- Create: `android/app/src/main/java/com/rxscan/app/data/net/PayloadMapper.kt`
- Create: `android/app/src/test/java/com/rxscan/app/data/net/PayloadMapperTest.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/data/net/Network.kt`

**Interfaces:**
- Produces: `AuthApi.requestOtp(OtpRequestDto)` · `AuthApi.verifyOtp(OtpVerifyRequestDto): OtpVerifyResponseDto`
  · `MeApi.putConsents(ConsentsPutRequestDto)` · `MeApi.putPreferences(PreferencesPutRequestDto)` ·
  `MeApi.getPreferences(): PreferencesGetResponseDto` · `PrescriptionApi.create(PrescriptionsPostRequestDto): PrescriptionsPostResponseDto`
  · `PrescriptionApi.update(rxId: String, PrescriptionsPostRequestDto): PrescriptionsPatchResponseDto` ·
  `PrescriptionApi.list(since: String?): PrescriptionsGetResponseDto` · `object TokenCache { var current: String? }`
  · `class ApiException(httpStatus: Int, code: String, message: String)` · `ApiErrors.from(HttpException): ApiException`
  · `fun List<Medication>.toMedsPayload(confirmedAt: String): MedsPayloadDto` ·
  `Network.authApi` / `Network.meApi` / `Network.prescriptionApi` (new lazy vals).
- Consumes: `com.rxscan.app.data.Medication` (existing, `data/Model.kt`) in `PayloadMapper.kt`.

- [ ] **Step 1: `AuthDtos.kt`**

  ```kotlin
  package com.rxscan.app.data.net

  import com.google.gson.annotations.SerializedName

  // Auth + consent wire DTOs (docs/api-contract-v1.md "Auth", "Me"). snake_case on the wire
  // via @SerializedName on the backend's own envelope fields only — the FE-owned payload
  // schemas stay camelCase (see PrescriptionDtos.kt / MeDtos.kt).

  data class OtpRequestDto(val phone: String)

  data class ConsentDto(
      val purpose: String, // "process" | "notify" | "retain_optin"
      val granted: Boolean,
      @SerializedName("granted_at") val grantedAt: String, // ISO8601, device-side grant time
  )

  data class OtpVerifyRequestDto(
      val phone: String,
      val otp: String,
      val consents: List<ConsentDto>,
  )

  data class OtpVerifyResponseDto(
      val token: String,
      @SerializedName("user_created") val userCreated: Boolean,
  )

  data class ConsentsPutRequestDto(val consents: List<ConsentDto>)
  ```

- [ ] **Step 2: `MeDtos.kt`**

  ```kotlin
  package com.rxscan.app.data.net

  import com.google.gson.annotations.SerializedName

  // Preferences wire DTOs (docs/api-contract-v1.md "PUT/GET /v1/me/preferences"). The
  // payload is FE-owned and server-opaque — additive-only, ours to grow.

  data class MealTimesDto(
      val breakfast: String, // "HH:mm"
      val lunch: String,
      val dinner: String,
  )

  data class PreferencesPayloadDto(
      val schema: Int = 1,
      val mealTimes: MealTimesDto,
  )

  data class PreferencesPutRequestDto(val payload: PreferencesPayloadDto)

  data class PreferencesGetResponseDto(
      val payload: PreferencesPayloadDto,
      @SerializedName("updated_at") val updatedAt: String,
  )
  ```

- [ ] **Step 3: `PrescriptionDtos.kt`**

  ```kotlin
  package com.rxscan.app.data.net

  import com.google.gson.annotations.SerializedName

  // Prescription wire DTOs (docs/api-contract-v1.md "Prescriptions"). No suggested_value
  // field exists anywhere here, by design (CDSCO: flag, don't correct).

  data class MedItemDto(
      val name: String,
      val strength: String?,
      val slots: List<String>, // "morning" | "afternoon" | "night"
      val mealTiming: String?, // "before_food" | "after_food" | null
      val durationDays: Int?,
      val prn: Boolean,
  )

  data class MedsPayloadDto(
      val schema: Int = 1,
      val meds: List<MedItemDto>,
      val confirmedAt: String, // ISO8601
  )

  data class PrescriptionsPostRequestDto(val payload: MedsPayloadDto)

  data class PrescriptionsPostResponseDto(
      @SerializedName("rx_id") val rxId: String,
      @SerializedName("updated_at") val updatedAt: String,
  )

  data class PrescriptionsPatchResponseDto(
      @SerializedName("updated_at") val updatedAt: String,
  )

  data class PrescriptionRecordDto(
      @SerializedName("rx_id") val rxId: String,
      val payload: MedsPayloadDto,
      @SerializedName("created_at") val createdAt: String,
      @SerializedName("updated_at") val updatedAt: String,
  )

  data class PrescriptionsGetResponseDto(val prescriptions: List<PrescriptionRecordDto>)
  ```

- [ ] **Step 4: `ApiError.kt`**

  ```kotlin
  package com.rxscan.app.data.net

  import com.google.gson.Gson
  import retrofit2.HttpException

  // Uniform error envelope (docs/api-contract-v1.md "Contract rules"): {"error":{code,message}}.

  data class ApiErrorBodyDto(val code: String, val message: String)
  data class ApiErrorEnvelopeDto(val error: ApiErrorBodyDto)

  /** A parsed backend error: HTTP status + machine code + human message. */
  class ApiException(val httpStatus: Int, val code: String, message: String) : Exception(message)

  object ApiErrors {
      private val gson = Gson()

      /** Parses the uniform {"error":{code,message}} envelope off a failed Retrofit call. */
      fun from(e: HttpException): ApiException {
          val raw = e.response()?.errorBody()?.string()
          val parsed = raw?.let { runCatching { gson.fromJson(it, ApiErrorEnvelopeDto::class.java) }.getOrNull() }
          return ApiException(
              httpStatus = e.code(),
              code = parsed?.error?.code ?: "unknown",
              message = parsed?.error?.message ?: (e.message() ?: "Request failed"),
          )
      }
  }
  ```

- [ ] **Step 5: `AuthApi.kt`, `MeApi.kt`, `PrescriptionApi.kt`**

  ```kotlin
  package com.rxscan.app.data.net

  import retrofit2.http.Body
  import retrofit2.http.POST

  /** Unauthenticated auth endpoints (docs/api-contract-v1.md "Auth"). */
  interface AuthApi {
      /** POST /v1/auth/otp/request — 200 {} on success, 422 invalid_phone. */
      @POST("v1/auth/otp/request")
      suspend fun requestOtp(@Body body: OtpRequestDto)

      /** POST /v1/auth/otp/verify — 200 {token, user_created}, 401 invalid_otp. */
      @POST("v1/auth/otp/verify")
      suspend fun verifyOtp(@Body body: OtpVerifyRequestDto): OtpVerifyResponseDto
  }
  ```

  ```kotlin
  package com.rxscan.app.data.net

  import retrofit2.http.Body
  import retrofit2.http.GET
  import retrofit2.http.PUT

  /** Bearer-authed consent + preference endpoints (docs/api-contract-v1.md "Me"). */
  interface MeApi {
      /** PUT /v1/me/consents — 204. Append-only rows; withdrawal = a new granted:false row. */
      @PUT("v1/me/consents")
      suspend fun putConsents(@Body body: ConsentsPutRequestDto)

      /** PUT /v1/me/preferences — 204. Upsert; exactly one row per user. */
      @PUT("v1/me/preferences")
      suspend fun putPreferences(@Body body: PreferencesPutRequestDto)

      /** GET /v1/me/preferences — 200 {payload, updated_at}, 404 if never set. */
      @GET("v1/me/preferences")
      suspend fun getPreferences(): PreferencesGetResponseDto
  }
  ```

  ```kotlin
  package com.rxscan.app.data.net

  import retrofit2.http.Body
  import retrofit2.http.GET
  import retrofit2.http.PATCH
  import retrofit2.http.POST
  import retrofit2.http.Path
  import retrofit2.http.Query

  /** Bearer-authed prescription sync (docs/api-contract-v1.md "Prescriptions"). */
  interface PrescriptionApi {
      /** POST /v1/prescriptions — 201 {rx_id, updated_at}. Fires right after OTP verify succeeds. */
      @POST("v1/prescriptions")
      suspend fun create(@Body body: PrescriptionsPostRequestDto): PrescriptionsPostResponseDto

      /** PATCH /v1/prescriptions/{rxId} — 200 {updated_at}, 404 (also for someone else's rxId). */
      @PATCH("v1/prescriptions/{rxId}")
      suspend fun update(@Path("rxId") rxId: String, @Body body: PrescriptionsPostRequestDto): PrescriptionsPatchResponseDto

      /** GET /v1/prescriptions?since= — 200 {prescriptions:[...]}. since absent ⇒ full pull. */
      @GET("v1/prescriptions")
      suspend fun list(@Query("since") since: String? = null): PrescriptionsGetResponseDto
  }
  ```

- [ ] **Step 6: `AuthInterceptor.kt` (also defines `TokenCache`)**

  ```kotlin
  package com.rxscan.app.data.net

  import okhttp3.Interceptor
  import okhttp3.Response

  /**
   * Adds `Authorization: Bearer <jwt>` to the consumer-plane routes only (contract:
   * /v1/me/** and /v1/prescriptions/**); /extract and /v1/auth/** stay open. Reads
   * [TokenCache] — a synchronous mirror of the DataStore-held JWT, since OkHttp
   * interceptors can't suspend.
   */
  class AuthInterceptor : Interceptor {
      override fun intercept(chain: Interceptor.Chain): Response {
          val request = chain.request()
          val path = request.url.encodedPath
          val needsAuth = path.startsWith("/v1/me/") || path.startsWith("/v1/prescriptions")
          val token = TokenCache.current
          val authed = if (needsAuth && token != null) {
              request.newBuilder().addHeader("Authorization", "Bearer $token").build()
          } else request
          return chain.proceed(authed)
      }
  }

  /**
   * Synchronous mirror of the JWT. `com.rxscan.app.data.local.RxScanStore` (Task 3)
   * updates this on every save/load — Preferences DataStore is Flow/suspend-only and
   * OkHttp interceptors run on a plain (non-suspend) thread.
   */
  object TokenCache {
      @Volatile var current: String? = null
  }
  ```

- [ ] **Step 7: `PayloadMapper.kt`**

  ```kotlin
  package com.rxscan.app.data.net

  import com.rxscan.app.data.Medication

  /**
   * Confirmed meds (data/Model.kt) → the FE-owned meds payload the backend stores
   * opaquely (docs/api-contract-v1.md). CDSCO: there is no suggested_value field here —
   * every value written is what the user confirmed on the Verify screen.
   */
  fun List<Medication>.toMedsPayload(confirmedAt: String): MedsPayloadDto = MedsPayloadDto(
      schema = 1,
      meds = map { it.toMedItemDto() },
      confirmedAt = confirmedAt,
  )

  private fun Medication.toMedItemDto(): MedItemDto = MedItemDto(
      name = name,
      strength = strength,
      slots = schedule.toSlots(),
      mealTiming = food.toMealTiming(),
      durationDays = if (prn) null else duration?.toDurationDays(),
      prn = prn,
  )

  // "Morning · Noon · Night · Bedtime" (display text, data/ExtractionRepository.kt's
  // toSchedule()) → wire slots. The wire schema (api-contract-v1.md) only defines
  // morning/afternoon/night; ponytail: a Bedtime-only slot has no wire equivalent yet,
  // so it's dropped here — add a "bedtime" wire slot if/when the backend schema grows one.
  private fun String.toSlots(): List<String> = buildList {
      val text = lowercase()
      if ("morning" in text) add("morning")
      if ("noon" in text || "afternoon" in text) add("afternoon")
      if ("night" in text) add("night")
  }

  private fun String.toMealTiming(): String? = when {
      "before" in lowercase() -> "before_food"
      "after" in lowercase() -> "after_food"
      else -> null
  }

  // e.g. "5 days · ends Wed, 15 Jul" or a free-typed "10 days" → leading integer;
  // "No fixed course" / anything without a leading number → null (PRN, ongoing courses).
  private fun String.toDurationDays(): Int? = Regex("^(\\d+)").find(trim())?.groupValues?.get(1)?.toIntOrNull()
  ```

- [ ] **Step 8: `Network.kt` — add the consumer-plane Retrofit + the three new APIs**

  Replace the entire file with:

  ```kotlin
  package com.rxscan.app.data.net

  import com.rxscan.app.BuildConfig
  import okhttp3.OkHttpClient
  import okhttp3.logging.HttpLoggingInterceptor
  import retrofit2.Retrofit
  import retrofit2.converter.gson.GsonConverterFactory
  import java.util.concurrent.TimeUnit

  /**
   * Base URL for the backend. The Android emulator reaches the host machine's
   * loopback via the special alias 10.0.2.2 (localhost = the emulator itself).
   * Change this to the host's LAN IP when running on a physical device.
   */
  object ApiConfig {
      const val BASE_URL = "http://10.0.2.2:8080/"
  }

  /** Lazily-built Retrofit + OkHttp stack. Vision reads can be slow, so timeouts are generous. */
  object Network {

      private val logging = HttpLoggingInterceptor().apply {
          level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
          else HttpLoggingInterceptor.Level.NONE
      }

      val extractionApi: ExtractionApi by lazy {
          val client = OkHttpClient.Builder()
              .connectTimeout(15, TimeUnit.SECONDS)
              .readTimeout(60, TimeUnit.SECONDS)
              .writeTimeout(60, TimeUnit.SECONDS)
              .addInterceptor(logging)
              .build()

          retrofitFor(client).create(ExtractionApi::class.java)
      }

      // Consumer plane (auth/me/prescriptions) — Bearer-authed via AuthInterceptor.
      // One shared Retrofit instance: the interceptor only adds the header for
      // /v1/me/** and /v1/prescriptions/**, so /v1/auth/** riding along is a no-op.
      private val consumerRetrofit by lazy {
          val client = OkHttpClient.Builder()
              .connectTimeout(15, TimeUnit.SECONDS)
              .readTimeout(30, TimeUnit.SECONDS)
              .writeTimeout(30, TimeUnit.SECONDS)
              .addInterceptor(AuthInterceptor())
              .addInterceptor(logging)
              .build()
          retrofitFor(client)
      }

      val authApi: AuthApi by lazy { consumerRetrofit.create(AuthApi::class.java) }
      val meApi: MeApi by lazy { consumerRetrofit.create(MeApi::class.java) }
      val prescriptionApi: PrescriptionApi by lazy { consumerRetrofit.create(PrescriptionApi::class.java) }

      private fun retrofitFor(client: OkHttpClient): Retrofit = Retrofit.Builder()
          .baseUrl(ApiConfig.BASE_URL)
          .client(client)
          .addConverterFactory(GsonConverterFactory.create())
          .build()
  }
  ```

- [ ] **Step 9: `PayloadMapperTest.kt` — plain JUnit, no Android framework, no live backend**

  ```kotlin
  package com.rxscan.app.data.net

  import com.google.gson.Gson
  import com.rxscan.app.data.Medication
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertNull
  import org.junit.Test

  class PayloadMapperTest {

      @Test
      fun `confirmed meds map to the wire schema exactly`() {
          val meds = listOf(
              Medication(
                  id = "m1", name = "Augmentin 625 Duo", strength = "625 mg",
                  ink = "x", schedule = "Morning · Night", food = "After food",
                  duration = "5 days · ends Wed, 15 Jul", aloud = "x",
              ),
              Medication(
                  id = "m4", name = "Dolo 650", strength = "650 mg",
                  ink = "x", schedule = "When needed (SOS)", food = "As written",
                  duration = "No fixed course", aloud = "x", prn = true,
              ),
          )

          val payload = meds.toMedsPayload("2026-07-23T10:30:00+05:30")

          assertEquals(1, payload.schema)
          assertEquals("2026-07-23T10:30:00+05:30", payload.confirmedAt)
          assertEquals(2, payload.meds.size)

          val augmentin = payload.meds[0]
          assertEquals("Augmentin 625 Duo", augmentin.name)
          assertEquals("625 mg", augmentin.strength)
          assertEquals(listOf("morning", "night"), augmentin.slots)
          assertEquals("after_food", augmentin.mealTiming)
          assertEquals(5, augmentin.durationDays)
          assertFalse(augmentin.prn)

          val dolo = payload.meds[1]
          assertEquals(emptyList<String>(), dolo.slots)
          assertNull(dolo.mealTiming)
          assertNull(dolo.durationDays) // PRN never carries a duration
          assertEquals(true, dolo.prn)
      }

      @Test
      fun `gson serializes the wire payload without a suggested_value field`() {
          val payload = MedsPayloadDto(
              schema = 1,
              meds = listOf(
                  MedItemDto(
                      name = "Dolo 650", strength = "650 mg", slots = listOf("morning"),
                      mealTiming = "after_food", durationDays = 5, prn = false,
                  ),
              ),
              confirmedAt = "2026-07-23T10:30:00+05:30",
          )
          val json = Gson().toJson(payload)
          assertFalse(json.contains("suggested_value")) // CDSCO: never present
          assertEquals(true, json.contains("\"confirmedAt\""))
      }
  }
  ```

- [ ] **Step 10: Verify**

  ```bash
  JAVA_HOME=/usr/local/opt/openjdk@21 /Users/ankitjain/rxscan/android/gradlew -p /Users/ankitjain/rxscan/android testDebugUnitTest --tests "com.rxscan.app.data.net.PayloadMapperTest"
  ```

  Expected: `BUILD SUCCESSFUL`, 2 tests run, 0 failures.

- [ ] **Step 11: Commit**

  ```bash
  cd /Users/ankitjain/rxscan && git add android/app/src/main/java/com/rxscan/app/data/net/ android/app/src/test/java/com/rxscan/app/data/net/
  git commit -m "android: consumer API DTOs, AuthApi/MeApi/PrescriptionApi, auth interceptor"
  ```

---

### Task 3: RxScanStore — DataStore-backed local state

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/data/local/RxScanStore.kt`

**Interfaces:**
- Consumes: `com.rxscan.app.data.net.TokenCache` (Task 2).
- Produces: `class RxScanStore(context: Context)` with `suspend fun saveToken(String?)` ·
  `suspend fun loadToken(): String?` · `suspend fun savePhone(String)` · `suspend fun loadPhone(): String?`
  · `suspend fun saveMealTimesJson(String)` · `suspend fun loadMealTimesJson(): String?` ·
  `suspend fun saveConsentsJson(String)` · `suspend fun loadConsentsJson(): String?` ·
  `suspend fun clearConsents()`.

- [ ] **Step 1: `RxScanStore.kt`**

  ```kotlin
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
  ```

- [ ] **Step 2: Verify (compile-only — DataStore needs a real Context, exercised manually in Task 7)**

  ```bash
  JAVA_HOME=/usr/local/opt/openjdk@21 /Users/ankitjain/rxscan/android/gradlew -p /Users/ankitjain/rxscan/android assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

  ```bash
  cd /Users/ankitjain/rxscan && git add android/app/src/main/java/com/rxscan/app/data/local/RxScanStore.kt
  git commit -m "android: RxScanStore — DataStore-backed JWT/phone/meal-times/consents"
  ```

---

### Task 4: Room — PrescriptionEntity/Dao/Database + PrescriptionRepository

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/data/local/PrescriptionEntity.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/local/PrescriptionDao.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/local/RxScanDatabase.kt`
- Create: `android/app/src/main/java/com/rxscan/app/data/PrescriptionRepository.kt`

**Interfaces:**
- Produces: `data class PrescriptionEntity(localId: Long, rxId: String?, payloadJson: String, pendingSync: Boolean, updatedAt: String)`
  · `PrescriptionDao.insert(entity): Long` · `PrescriptionDao.pendingSync(): List<PrescriptionEntity>` ·
  `PrescriptionDao.markSynced(localId: Long, rxId: String, updatedAt: String)` ·
  `class PrescriptionRepository(context: Context)` with `suspend fun saveDraft(payloadJson: String): Long`
  · `suspend fun pendingSync(): List<PrescriptionEntity>` · `suspend fun markSynced(localId: Long, rxId: String, updatedAt: String)`.

- [ ] **Step 1: `PrescriptionEntity.kt`**

  ```kotlin
  package com.rxscan.app.data.local

  import androidx.room.Entity
  import androidx.room.PrimaryKey

  /**
   * Disposable local cache of a confirmed prescription (spec "FE bindings + store").
   * rxId is null / pendingSync=true until the post-login POST/PATCH assigns a server id.
   * Deliberately unnormalized — med/dose tables arrive with the alarm+adherence slice.
   */
  @Entity(tableName = "prescriptions")
  data class PrescriptionEntity(
      @PrimaryKey(autoGenerate = true) val localId: Long = 0,
      val rxId: String?,
      val payloadJson: String,
      val pendingSync: Boolean,
      val updatedAt: String,
  )
  ```

- [ ] **Step 2: `PrescriptionDao.kt`**

  ```kotlin
  package com.rxscan.app.data.local

  import androidx.room.Dao
  import androidx.room.Insert
  import androidx.room.Query

  @Dao
  interface PrescriptionDao {
      @Insert
      suspend fun insert(entity: PrescriptionEntity): Long

      @Query("SELECT * FROM prescriptions WHERE pendingSync = 1")
      suspend fun pendingSync(): List<PrescriptionEntity>

      @Query("UPDATE prescriptions SET rxId = :rxId, pendingSync = 0, updatedAt = :updatedAt WHERE localId = :localId")
      suspend fun markSynced(localId: Long, rxId: String, updatedAt: String)
  }
  ```

- [ ] **Step 3: `RxScanDatabase.kt`**

  ```kotlin
  package com.rxscan.app.data.local

  import androidx.room.Database
  import androidx.room.RoomDatabase

  // exportSchema=false: a single-entity dev-phase cache, no versioned migrations yet.
  // Add schema export + real Migration objects when the alarm/adherence slice needs them.
  @Database(entities = [PrescriptionEntity::class], version = 1, exportSchema = false)
  abstract class RxScanDatabase : RoomDatabase() {
      abstract fun prescriptionDao(): PrescriptionDao
  }
  ```

- [ ] **Step 4: `PrescriptionRepository.kt`**

  ```kotlin
  package com.rxscan.app.data

  import android.content.Context
  import androidx.room.Room
  import com.rxscan.app.data.local.PrescriptionEntity
  import com.rxscan.app.data.local.RxScanDatabase
  import java.time.OffsetDateTime

  private object RxScanDatabaseHolder {
      @Volatile private var instance: RxScanDatabase? = null
      fun get(context: Context): RxScanDatabase = instance ?: synchronized(this) {
          instance ?: Room.databaseBuilder(context.applicationContext, RxScanDatabase::class.java, "rxscan.db")
              .build().also { instance = it }
      }
  }

  /**
   * Disposable local cache of the server's prescription record (spec "FE bindings +
   * store"). A confirmed prescription is saved here the moment Verify's hard gate
   * passes — rxId is null / pendingSync is true until the post-login POST/PATCH
   * assigns a server id (see SyncRepository).
   */
  class PrescriptionRepository(context: Context) {
      private val dao = RxScanDatabaseHolder.get(context).prescriptionDao()

      suspend fun saveDraft(payloadJson: String): Long = dao.insert(
          PrescriptionEntity(
              rxId = null,
              payloadJson = payloadJson,
              pendingSync = true,
              updatedAt = OffsetDateTime.now().toString(),
          ),
      )

      suspend fun pendingSync(): List<PrescriptionEntity> = dao.pendingSync()

      suspend fun markSynced(localId: Long, rxId: String, updatedAt: String) {
          dao.markSynced(localId, rxId, updatedAt)
      }
  }
  ```

- [ ] **Step 5: Verify (KSP generates Room's implementation — this catches any Entity/Dao mistakes)**

  ```bash
  JAVA_HOME=/usr/local/opt/openjdk@21 /Users/ankitjain/rxscan/android/gradlew -p /Users/ankitjain/rxscan/android assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL` (look for the `kspDebugKotlin` task in the log — confirms Room's
  annotation processor ran).

- [ ] **Step 6: Commit**

  ```bash
  cd /Users/ankitjain/rxscan && git add android/app/src/main/java/com/rxscan/app/data/local/PrescriptionEntity.kt \
          android/app/src/main/java/com/rxscan/app/data/local/PrescriptionDao.kt \
          android/app/src/main/java/com/rxscan/app/data/local/RxScanDatabase.kt \
          android/app/src/main/java/com/rxscan/app/data/PrescriptionRepository.kt
  git commit -m "android: Room PrescriptionEntity/Dao/Database + PrescriptionRepository"
  ```

---

### Task 5: SyncRepository — post-OTP sync orchestration

**Files:**
- Create: `android/app/src/main/java/com/rxscan/app/data/SyncRepository.kt`

**Interfaces:**
- Consumes: `Network.authApi/meApi/prescriptionApi` (Task 2), `RxScanStore` (Task 3),
  `PrescriptionRepository` (Task 4).
- Produces: `sealed interface SyncOutcome { Success(userCreated: Boolean), InvalidOtp, AuthExpired, Network, Failure }`
  · `class SyncRepository(context: Context)` with `suspend fun verifyOtpAndSync(phone: String, otp: String): SyncOutcome`
  · `suspend fun pushNotifyConsent(granted: Boolean, grantedAt: String): SyncOutcome`.

- [ ] **Step 1: `SyncRepository.kt`**

  ```kotlin
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

      suspend fun verifyOtpAndSync(phone: String, otp: String): SyncOutcome {
          val consents = store.loadConsentsJson()
              ?.let { gson.fromJson(it, Array<ConsentDto>::class.java).toList() }
              ?: emptyList()

          val response = try {
              Network.authApi.verifyOtp(OtpVerifyRequestDto(phone, otp, consents))
          } catch (e: HttpException) {
              return if (e.code() == 401) SyncOutcome.InvalidOtp else SyncOutcome.Failure
          } catch (_: IOException) {
              return SyncOutcome.Network
          }

          store.saveToken(response.token)
          store.clearConsents()

          return try {
              pushPendingPrescriptions()
              pushPreferences()
              SyncOutcome.Success(response.userCreated)
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

      /** notify consent, sent after the notif-permission screen (spec: arrives after login). */
      suspend fun pushNotifyConsent(granted: Boolean, grantedAt: String): SyncOutcome = try {
          Network.meApi.putConsents(ConsentsPutRequestDto(listOf(ConsentDto("notify", granted, grantedAt))))
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
  ```

- [ ] **Step 2: Verify (compile-only — this class is exercised end-to-end manually in Task 7,**
  **per the "no automated tests hit a live backend" constraint)**

  ```bash
  JAVA_HOME=/usr/local/opt/openjdk@21 /Users/ankitjain/rxscan/android/gradlew -p /Users/ankitjain/rxscan/android assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

  ```bash
  cd /Users/ankitjain/rxscan && git add android/app/src/main/java/com/rxscan/app/data/SyncRepository.kt
  git commit -m "android: SyncRepository — post-OTP prescription + preferences push"
  ```

---

### Task 6: Rewire ConsentScreen/VerifyScreen/MealTimesScreen/OtpScreen + RxScanNav to the real API

**Files:**
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/ConsentScreen.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/VerifyScreen.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/MealTimesScreen.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/screens/OtpScreen.kt`
- Modify: `android/app/src/main/java/com/rxscan/app/ui/RxScanNav.kt`

**Interfaces:**
- Changes: `ConsentScreen(onContinue: () -> Unit)` → `ConsentScreen(onContinue: (process: Boolean, retainOptIn: Boolean) -> Unit)`.
- Changes: `VerifyScreen(meds, onAllConfirmed: () -> Unit)` → `VerifyScreen(meds, onAllConfirmed: (List<Medication>) -> Unit)`.
- Changes: `MealTimesScreen(onSave: () -> Unit)` → `MealTimesScreen(onSave: (breakfast: Int, lunch: Int, dinner: Int) -> Unit)`.
- Changes: `OtpScreen(phone, onBack, onVerified: () -> Unit)` →
  `OtpScreen(phone, onBack, onVerify: suspend (code: String) -> Boolean, onResend: () -> Unit, onVerified: () -> Unit)`.
- `NotifPermScreen` is unchanged — its existing `onResult: (allowed: Boolean) -> Unit` already
  carries what the notify-consent push needs.
- Consumes (in `RxScanNav.kt`): `RxScanStore`, `PrescriptionRepository`, `SyncRepository`,
  `SyncOutcome`, `ConsentDto`, `MealTimesDto`, `PreferencesPayloadDto`, `Network.authApi`,
  `OtpRequestDto`, `toMedsPayload` — all from Tasks 2–5.

- [ ] **Step 1: `ConsentScreen.kt` — surface the two consent choices**

  Replace:

  ```kotlin
  @Composable
  fun ConsentScreen(onContinue: () -> Unit) {
  ```

  With:

  ```kotlin
  @Composable
  fun ConsentScreen(onContinue: (process: Boolean, retainOptIn: Boolean) -> Unit) {
  ```

  Replace:

  ```kotlin
          Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
              PrimaryButton(
                  text = if (processOn) "Agree & continue" else "Turn on the first permission",
                  onClick = onContinue,
                  enabled = processOn,
              )
          }
  ```

  With:

  ```kotlin
          Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
              PrimaryButton(
                  text = if (processOn) "Agree & continue" else "Turn on the first permission",
                  onClick = { onContinue(processOn, effectiveRetain) },
                  enabled = processOn,
              )
          }
  ```

- [ ] **Step 2: `VerifyScreen.kt` — surface the final confirmed meds**

  Replace:

  ```kotlin
  @Composable
  fun VerifyScreen(meds: List<Medication>, onAllConfirmed: () -> Unit) {
  ```

  With:

  ```kotlin
  @Composable
  fun VerifyScreen(meds: List<Medication>, onAllConfirmed: (List<Medication>) -> Unit) {
  ```

  Replace:

  ```kotlin
              PrimaryButton(
                  text = if (allConfirmed) "Continue — set meal times" else "Confirm each medicine to continue",
                  onClick = onAllConfirmed,
                  enabled = allConfirmed,
              )
  ```

  With:

  ```kotlin
              PrimaryButton(
                  text = if (allConfirmed) "Continue — set meal times" else "Confirm each medicine to continue",
                  onClick = {
                      val finalMeds = meds.map { med ->
                          med.copy(
                              name = nameEdits[med.id] ?: med.name,
                              strength = displayStrength(med, resolved[med.id], strengthEdits[med.id]),
                              schedule = scheduleEdits[med.id] ?: med.schedule,
                              food = foodEdits[med.id] ?: med.food,
                              duration = displayDuration(med, resolved[med.id], durationEdits[med.id]),
                          )
                      }
                      onAllConfirmed(finalMeds)
                  },
                  enabled = allConfirmed,
              )
  ```

- [ ] **Step 3: `MealTimesScreen.kt` — surface the three wire-relevant meal times**

  Replace:

  ```kotlin
  @Composable
  fun MealTimesScreen(onSave: () -> Unit) {
  ```

  With:

  ```kotlin
  @Composable
  fun MealTimesScreen(onSave: (breakfast: Int, lunch: Int, dinner: Int) -> Unit) {
  ```

  Replace:

  ```kotlin
          Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
              PrimaryButton("Set my reminders", onClick = onSave)
          }
  ```

  With:

  ```kotlin
          Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
              PrimaryButton("Set my reminders", onClick = { onSave(breakfast, lunch, dinner) })
          }
  ```

  (`bedtime` stays local-only UI state — the wire schema, `docs/api-contract-v1.md`, only defines
  `breakfast`/`lunch`/`dinner`.)

- [ ] **Step 4: `OtpScreen.kt` — real verify/resend, demo branch removed**

  Replace the entire file with:

  ```kotlin
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
  import androidx.compose.runtime.rememberCoroutineScope
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
  import kotlinx.coroutines.launch

  /**
   * OTP (design: scr-otp). One-time code verifies the number and creates the
   * account; on success the reminders schedule immediately — the ask-reward loop
   * is seconds long. [onVerify] calls the real POST /v1/auth/otp/verify (via
   * SyncRepository) and returns whether it succeeded; [onResend] re-fires
   * POST /v1/auth/otp/request. Dev stub OTP is 000000 (rxscan.auth.dev-otp).
   */
  @Composable
  fun OtpScreen(
      phone: String,
      onBack: () -> Unit,
      onVerify: suspend (code: String) -> Boolean,
      onResend: () -> Unit,
      onVerified: () -> Unit,
  ) {
      var code by rememberSaveable { mutableStateOf("") }
      var error by rememberSaveable { mutableStateOf(false) }
      var verifying by rememberSaveable { mutableStateOf(false) }
      var secondsLeft by rememberSaveable { mutableIntStateOf(30) }
      val scope = rememberCoroutineScope()

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
                              onResend()
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
          }

          Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
              PrimaryButton(
                  if (verifying) "Verifying…" else "Verify",
                  onClick = {
                      verifying = true
                      scope.launch {
                          val ok = onVerify(code)
                          verifying = false
                          if (ok) onVerified() else error = true
                      }
                  },
                  enabled = code.length == 6 && !verifying,
              )
          }
      }
  }
  ```

  (Removed: the mocked "any 6 digits verify / 000000 shows an error" branch and the
  "Demo: any 6 digits verify…" hint text — both are now factually wrong, since `000000`
  is the real dev-accepted stub code.)

- [ ] **Step 5: `RxScanNav.kt` — wire consent/verify/mealtimes/signin/otp/notifperm to the real API**

  Replace the entire file with:

  ```kotlin
  package com.rxscan.app.ui

  import android.net.Uri
  import androidx.compose.runtime.Composable
  import androidx.compose.runtime.LaunchedEffect
  import androidx.compose.runtime.getValue
  import androidx.compose.runtime.mutableStateOf
  import androidx.compose.runtime.remember
  import androidx.compose.runtime.rememberCoroutineScope
  import androidx.compose.runtime.saveable.rememberSaveable
  import androidx.compose.runtime.setValue
  import androidx.compose.ui.platform.LocalContext
  import androidx.navigation.compose.NavHost
  import androidx.navigation.compose.composable
  import androidx.navigation.compose.rememberNavController
  import com.google.gson.Gson
  import com.rxscan.app.data.PrescriptionRepository
  import com.rxscan.app.data.SyncOutcome
  import com.rxscan.app.data.SyncRepository
  import com.rxscan.app.data.local.RxScanStore
  import com.rxscan.app.data.net.ConsentDto
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
  import com.rxscan.app.ui.screens.WelcomeScreen
  import java.time.OffsetDateTime
  import kotlinx.coroutines.launch

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

      // Hydrate the OkHttp interceptor's synchronous token cache once, at process start
      // (covers a warm process that's still holding a prior session's JWT).
      LaunchedEffect(Unit) { store.loadToken() }

      // Phone number hoisted here so signin → otp share it.
      var phone by rememberSaveable { mutableStateOf("") }
      // Notification choice hoisted so Today can show the persistent silenced banner (PRD §6.4).
      var notifAllowed by rememberSaveable { mutableStateOf(true) }
      // Captured/picked prescription image, threaded capture → extracting. Plain remember:
      // a transient cache-file URI needn't survive process death for this UI pass.
      var capturedUri by remember { mutableStateOf<Uri?>(null) }
      // Real extracted medicines (from POST /extract), threaded extracting → verify → mealtimes.
      var meds by remember { mutableStateOf<List<com.rxscan.app.data.Medication>>(emptyList()) }

      NavHost(navController = nav, startDestination = Routes.WELCOME) {
          composable(Routes.WELCOME) {
              WelcomeScreen(onGetStarted = { nav.navigate(Routes.CONSENT) })
          }
          composable(Routes.CONSENT) {
              ConsentScreen(onContinue = { process, retainOptIn ->
                  // Pre-login consents: held in DataStore until the OTP verify upload
                  // (spec: process/retain_optin piggyback on the verify call).
                  val now = OffsetDateTime.now().toString()
                  val consents = listOf(
                      ConsentDto("process", process, now),
                      ConsentDto("retain_optin", retainOptIn, now),
                  )
                  scope.launch { store.saveConsentsJson(gson.toJson(consents)) }
                  nav.navigate(Routes.CAPTURE)
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
                      // rxId=null); the POST fires once OTP verify succeeds (spec: deferred save).
                      val payload = confirmedMeds.toMedsPayload(OffsetDateTime.now().toString())
                      scope.launch { prescriptions.saveDraft(gson.toJson(payload)) }
                      nav.navigate(Routes.MEAL_TIMES)
                  },
              )
          }
          composable(Routes.MEAL_TIMES) {
              // "Set my reminders" = the save moment → deferred sign-in (Q13).
              MealTimesScreen(onSave = { breakfast, lunch, dinner ->
                  val payload = PreferencesPayloadDto(
                      mealTimes = MealTimesDto(
                          breakfast = minutesToHHmm(breakfast),
                          lunch = minutesToHHmm(lunch),
                          dinner = minutesToHHmm(dinner),
                      ),
                  )
                  scope.launch { store.saveMealTimesJson(gson.toJson(payload)) }
                  nav.navigate(Routes.SIGNIN)
              })
          }
          composable(Routes.SIGNIN) {
              SignInScreen(
                  onBack = { nav.popBackStack() },
                  onSendCode = {
                      phone = it
                      scope.launch {
                          store.savePhone(it)
                          runCatching { Network.authApi.requestOtp(OtpRequestDto(it)) }
                      }
                      nav.navigate(Routes.OTP)
                  },
                  // Login is optional (user opted not to force it). Skip straight to
                  // notification setup without an account; nothing is synced server-side.
                  onSkip = {
                      phone = ""
                      nav.navigate(Routes.NOTIF_PERM)
                  },
              )
          }
          composable(Routes.OTP) {
              OtpScreen(
                  phone = phone,
                  onBack = { nav.popBackStack() },
                  onVerify = { code -> sync.verifyOtpAndSync(phone, code) is SyncOutcome.Success },
                  onResend = {
                      scope.launch { runCatching { Network.authApi.requestOtp(OtpRequestDto(phone)) } }
                  },
                  onVerified = { nav.navigate(Routes.NOTIF_PERM) },
              )
          }
          composable(Routes.NOTIF_PERM) {
              NotifPermScreen(onResult = { allowed ->
                  // Allow or deny: everything is still saved; denial shows the persistent
                  // silenced banner on Today. The notify consent PUTs either way — a
                  // denial IS the recorded choice (spec: notify consent arrives here).
                  notifAllowed = allowed
                  scope.launch {
                      val outcome = sync.pushNotifyConsent(allowed, OffsetDateTime.now().toString())
                      if (outcome is SyncOutcome.AuthExpired) {
                          // Contract rule: any 401 ⇒ clear the token and route to signin.
                          nav.navigate(Routes.SIGNIN) { popUpTo(Routes.WELCOME) { inclusive = true } }
                      } else {
                          nav.navigate(Routes.TODAY) { popUpTo(Routes.WELCOME) { inclusive = true } }
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
  ```

- [ ] **Step 6: Verify**

  ```bash
  JAVA_HOME=/usr/local/opt/openjdk@21 /Users/ankitjain/rxscan/android/gradlew -p /Users/ankitjain/rxscan/android assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL` (this is the real integration-compile check — any signature
  mismatch between a screen and `RxScanNav.kt` fails here).

- [ ] **Step 7: Commit**

  ```bash
  cd /Users/ankitjain/rxscan && git add android/app/src/main/java/com/rxscan/app/ui/screens/ConsentScreen.kt \
          android/app/src/main/java/com/rxscan/app/ui/screens/VerifyScreen.kt \
          android/app/src/main/java/com/rxscan/app/ui/screens/MealTimesScreen.kt \
          android/app/src/main/java/com/rxscan/app/ui/screens/OtpScreen.kt \
          android/app/src/main/java/com/rxscan/app/ui/RxScanNav.kt
  git commit -m "android: wire signin/otp/notifperm to the real consumer API"
  ```

---

### Task 7: Final build + manual emulator smoke checklist

**Files:**
- Modify: `/Users/ankitjain/rxscan/CLAUDE.md` (finalize Current status + date)

**Interfaces:** none — this task verifies Tasks 1–6 end to end against a real, locally running backend.

- [ ] **Step 1: Full release-shape compile**

  ```bash
  JAVA_HOME=/usr/local/opt/openjdk@21 /Users/ankitjain/rxscan/android/gradlew -p /Users/ankitjain/rxscan/android assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL`. APK at `android/app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Start the backend (stub OTP `000000`, default config)**

  ```bash
  cd /Users/ankitjain/rxscan/backend && mvn -q spring-boot:run &
  sleep 5 && curl -sf http://localhost:8080/health   # expect {"consumer":"up","engine":"up"}
  ```

- [ ] **Step 3: Boot the light emulator and install (exact commands from CLAUDE.md)**

  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd rxscan_light \
    -gpu swiftshader_indirect -no-snapshot -no-boot-anim -memory 1536 -cores 2 &
  # wait ~30s for boot, then:
  ADB=~/Library/Android/sdk/platform-tools/adb
  $ADB install -r /Users/ankitjain/rxscan/android/app/build/outputs/apk/debug/app-debug.apk
  $ADB shell am start -n com.rxscan.app/.MainActivity
  ```

- [ ] **Step 4: Manual walkthrough (human-in-the-loop — this is the "MANUAL via emulator" step;**
  **do not script this as an automated test, per the no-live-backend-in-tests constraint)**

  1. Welcome → Get started → Consent: turn on "Read this prescription" (and optionally "Help
     improve accuracy") → continue.
  2. Capture: use the gallery-picker fallback (or camera) to supply a prescription photo →
     Extracting runs the real `POST /extract` → Verify.
  3. Verify: resolve any flags (empty input, never pre-filled) → confirm all medicines →
     "Continue — set meal times".
  4. Meal times: adjust if desired → "Set my reminders" → Sign in.
  5. Sign in: enter any 10-digit number → "Send code" (fires `POST /v1/auth/otp/request`) → OTP screen.
  6. OTP: enter `000000` → "Verify". Expect: no error, navigates to the notification-permission screen.
  7. Notification permission: tap "Allow" or "Don't allow" → navigates to Today.
  8. Confirm against the backend:
     ```bash
     psql -d rxscan_consumer -tAc "SELECT count(*) FROM users"          # expect 1
     psql -d rxscan_consumer -tAc "SELECT count(*) FROM prescription"   # expect 1
     psql -d rxscan_consumer -tAc "SELECT count(*) FROM user_preference" # expect 1
     psql -d rxscan_consumer -tAc "SELECT purpose, granted FROM user_consent ORDER BY created_at"
     # expect: process|t, retain_optin|(t or f per the toggle), notify|(t or f per Allow/Don't allow)
     ```
  9. Screenshot for the record:
     ```bash
     $ADB exec-out screencap -p > /Users/ankitjain/rxscan/android/smoke-today.png
     ```
  10. Stop the backend: `pkill -f com.rxscan.backend` (or `fg` + Ctrl-C the `mvn spring-boot:run` job).

- [ ] **Step 5: Finalize CLAUDE.md**

  Update "Current status" (+ date): FE wiring to consumer API v1 DONE — real
  signin/OTP/notifperm against the local backend (stub OTP `000000`), DataStore
  (`RxScanStore`) + Room (`PrescriptionEntity`) added, confirmed meds sync via
  `SyncRepository` right after OTP verify, meal-time preferences pushed alongside.
  Remove the "FE wiring in progress" pending line from Task 1.

- [ ] **Step 6: Commit**

  ```bash
  cd /Users/ankitjain/rxscan && git add CLAUDE.md
  git commit -m "android: consumer API v1 FE wiring — build verification + smoke checklist"
  ```

---

## Self-Review

- **Spec FE-bindings coverage:** `data/net/` gets `AuthApi`, `MeApi` (consents + preferences),
  `PrescriptionApi` beside the existing `ExtractionApi`, sharing one OkHttp client with an auth
  interceptor injecting Bearer from the store (Task 2) ✓. DataStore holds JWT/phone/meal
  times/pre-login consents via a plain `RxScanStore` class (Task 3) ✓. Room holds
  `PrescriptionEntity(localId, rxId nullable, payloadJson, pendingSync, updatedAt)` + DAO, a
  disposable cache with `rxId` null / `pendingSync=true` until the post-login save (Task 4) ✓.
  Signin/OTP screens rewired from mock to real calls (Task 6) ✓. Both FE-owned payload schemas
  (meds, preferences) match `docs/api-contract-v1.md` exactly, field for field (Task 2 DTOs).
  App-flow alignment (login LATE, deferred prescription save, notify consent after login, 401 ⇒
  signin) is implemented in Task 5/6 exactly as the spec's "App-flow alignment" section describes.
- **Placeholder scan:** none — every file in every task is complete, runnable code; no `TBD`, no
  "similar to task N" reuse-by-reference. `ConsentScreen`/`VerifyScreen`/`MealTimesScreen`/
  `OtpScreen`/`RxScanNav` diffs are given as exact before/after blocks or full-file replacements.
- **Type consistency:** `ConsentDto(purpose: String, granted: Boolean, grantedAt: String)` is the
  one shape used by both the pre-login consents (`ConsentScreen`→`RxScanNav`→`RxScanStore`) and the
  post-login notify consent (`SyncRepository.pushNotifyConsent`) — same constructor, same call
  shape, in Tasks 2/5/6. `PrescriptionEntity` (Task 4) is written by `PrescriptionRepository.saveDraft`
  and read by `SyncRepository.pushPendingPrescriptions` (Task 5) with matching field names
  (`rxId`, `payloadJson`, `pendingSync`, `localId`, `updatedAt`) throughout. `MedsPayloadDto` /
  `PreferencesPayloadDto` are produced once (`PayloadMapper.toMedsPayload`, `RxScanNav`'s mealtimes
  handler) and consumed with the identical Gson shape in `SyncRepository`. `SyncOutcome` is a
  closed `sealed interface` with every branch handled at both call sites (`RxScanNav`'s OTP and
  notif-perm wiring).
- **Ambiguities resolved while reading the code (not in the original suggested task split):**
  1. `ConsentScreen.onContinue`, `VerifyScreen.onAllConfirmed`, and `MealTimesScreen.onSave` were
     all `() -> Unit` in the current code — none of them exposed the data the spec's Room/DataStore
     design needs (confirmed meds, meal times, consent choices). Resolved by widening each
     signature to pass its screen's already-computed local state upward, folded into Task 6
     (rewiring) rather than a separate task, since the screens' visual behavior is unchanged —
     only what leaves the composable changes.
  2. The wire schema (`docs/api-contract-v1.md`) defines meal-time preferences as
     `{breakfast,lunch,dinner}` only, but `MealTimesScreen` also has a `bedtime` slider and the
     extraction mapper produces a "Bedtime" schedule token with no wire slot. Resolved by dropping
     both from the wire payload (documented with a `ponytail:` comment in `PayloadMapper.kt` and a
     plain code comment in the `MealTimesScreen.kt` diff) — upgrade path is adding a `bedtime` field
     to both schemas together if/when a real HS-timed medicine needs one.
  3. `OtpScreen`'s existing demo copy ("type 000000 to see the error state") is now backwards,
     since `000000` is the real dev-accepted stub OTP — removed rather than reconciled, since
     keeping any hard-coded demo branch would fight the real `onVerify` call.
  4. Phone-request/resend failures (`invalid_phone`, `otp_delivery_failed`) are not surfaced to the
     user in this pass — `runCatching { ... }` swallows them at the `RxScanNav` call sites. This
     matches CLAUDE.md's own "Not yet done: ... error states beyond OTP (honest-failure/offline)"
     note; richer error UI is an explicit follow-up, not silently dropped scope.
- **Testing posture matches the constraint exactly:** `PayloadMapperTest` (Task 2) is the only new
  automated test — plain JUnit, pure Kotlin, no Context, no network, no AI. Every other new class
  that needs a real Context (`RxScanStore`, `PrescriptionRepository`) or a real backend
  (`SyncRepository`) is verified by compilation in its own task and end-to-end by the Task 7 manual
  emulator + `psql` walkthrough — never by an automated test that would hit a live backend or AI API.
