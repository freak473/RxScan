# Connect Android app to the /extract backend — design

**Date:** 2026-07-22
**Status:** Approved (design), pending implementation
**Scope:** Android app (`android/`). Wire the real `POST /extract` call so Verify
shows real extracted medicines instead of `MockData`. Backend runs locally in
IntelliJ on port 8080.

## Goal

Replace the mocked extraction (fixed delay → `MockData.prescription`) with a
real multipart upload of the captured/picked photo to the backend, mapping the
response into the app's `Medication` model and rendering it on Verify. Honest
loading + error states; nothing fake on failure.

## Backend contract (read from source)

`POST /extract`, `multipart/form-data`, part **`image`** (`image/jpeg|png|webp`,
≤10 MB) →
- `200`: `{ "medicines": [ MedParseResult, … ] }`
  - `MedParseResult`: `drug{value,formularyId,confidence}`,
    `strength{value,confidence}`,
    `frequency{raw,slots{morning,noon,night,bedtime},pattern,confidence}`,
    `mealTiming{value,confidence}` (`value` ∈ BEFORE|AFTER|WITH or null),
    `duration{type,days,confidence}` (`type` ∈ DAYS|ONGOING|UNSPECIFIED),
    `flags[{field,reason}]` (`field` ∈ DRUG|STRENGTH|FREQUENCY|MEAL|DURATION;
    `reason` ∈ FREQ_UNRECOGNIZED|FREQ_NON_DAILY|STRENGTH_ANOMALY|
    STRENGTH_UNREADABLE|NAME_LOW_CONFIDENCE|DURATION_UNCLEAR|FIELD_LOW_CONFIDENCE)
- `400` empty image · `415` bad type · `413` too large · **`503 {"message"}`**
  when the vision model isn't configured.

**Operational preconditions (not code):**
- Backend must have `GEMINI_API_KEY` (or `rxscan.vision.api-key`) set, else every
  call is `503`.
- Emulator reaches the host Mac at **`http://10.0.2.2:8080`** (not `localhost`).

## Decisions (locked)

- **HTTP stack:** Retrofit + Gson converter + OkHttp logging interceptor.
- **Architecture:** a lightweight `ExtractionViewModel` (AndroidViewModel) exposing
  `Loading / Success(meds) / Error(kind)`.
- **Flag mapping:** map `STRENGTH_*`→`STRENGTH`, `DURATION_*`→`DURATION`; route
  every other reason (esp. `FREQ_NON_DAILY`) into a **generic re-check box** — new
  `FlagKind.OTHER`, empty input, names the field, still forces confirm. No high-harm
  flag is ever dropped (CLAUDE.md invariant).
- **On failure:** honest error state on the Extracting screen with **Retry** +
  back-to-capture; distinct copy for 503 / network / unreadable. No fallback to mock.

## Components

### 1. Networking (`data/net/`)
- `ApiConfig.kt`: `const val BASE_URL = "http://10.0.2.2:8080/"` (single place to
  change for a physical device / LAN IP).
- `dto/ExtractionDtos.kt`: Gson data classes mirroring the JSON above. Enums kept
  as `String` (tolerant to unknown values).
- `ExtractionApi.kt` (Retrofit):
  `@Multipart @POST("extract") suspend fun extract(@Part image: MultipartBody.Part): ExtractionResponseDto`
- `Network.kt`: builds OkHttp (30s timeouts, `HttpLoggingInterceptor` on debug) +
  Retrofit with the Gson converter.

### 2. Repository + mapping (`data/ExtractionRepository.kt`)
- `suspend fun extract(uri: Uri): List<Medication>`:
  read bytes via `contentResolver.openInputStream(uri)`, wrap as a
  `MultipartBody.Part` named `image` with `image/jpeg`, call the API, map each DTO
  → `Medication`.
- Mapping (`MedParseResultDto.toMedication(index)`):
  - `name` = `drug.value`; `strength` = `strength.value?.ifBlank { null }`
  - `ink` = `frequency.raw` (best available "from your paper" text; imperfect —
    backend has no single reconstructed line. Noted as a follow-up.)
  - `schedule` = non-zero `slots` → "Morning · Noon · Night · Bedtime" join
  - `food` = mealTiming → "Before food" / "After food" / "With food" / ""
  - `duration` = DAYS→"N days" · ONGOING→"Ongoing" · UNSPECIFIED→`null`
  - `aloud` = non-advisory "…your prescription says…" string built from the above
  - `prn` = `pattern ∈ {PRN, STAT}`
  - `flag` = highest-priority flag mapped to `ReCheckFlag` (priority:
    FREQ_NON_DAILY/FREQ_UNRECOGNIZED > STRENGTH > DURATION > NAME/MEAL/FIELD).
    The current model holds one flag per med; if several, the top-priority one is
    shown (documented limitation). Empty input always (no suggested value).

### 3. Model change (`data/Model.kt`)
- `enum class FlagKind { STRENGTH, DURATION, OTHER }`. `OTHER` reuses the flag-box
  UI with a free-text input and non-empty validation; its title/body name the
  flagged field in plain language.

### 4. ViewModel (`ui/ExtractionViewModel.kt`)
- `AndroidViewModel`; `state: StateFlow<ExtractionState>`.
- `fun run(uri: Uri)` → `viewModelScope.launch` calls the repository; maps
  `HttpException(503)`, other `HttpException`, `IOException`, and empty-list into
  `Error(kind)`; success → `Success(meds)`.
- `sealed interface ExtractionState { Loading; data class Success; data class Error }`.

### 5. Screen wiring
- **`ExtractingScreen`** gains the VM: `LaunchedEffect(imageUri) { vm.run(uri) }`,
  observes state. `Loading` → the existing animated steps (now indeterminate, not a
  fixed 3.9s timer). `Success` → `onExtracted(meds)`. `Error` → error panel (icon +
  plain-language cause + **Retry** re-runs, **Back** → capture). Still renders the
  real photo via Coil while loading.
- **`RxScanNav`**: hoist `var meds by remember { mutableStateOf<List<Medication>>(emptyList()) }`;
  `ExtractingScreen(imageUri, onExtracted = { meds = it; navigate(VERIFY) }, onBack = { popBackStack() })`;
  `VerifyScreen(meds = meds, …)`.
- **`VerifyScreen`**: signature gains `meds: List<Medication>`, replaces
  `MockData.prescription`. Handle the new `FlagKind.OTHER` in flag rendering
  (free-text, non-empty). `MockData` stays for `@Preview`/DoctorShare.

### 6. Manifest / permissions (`AndroidManifest.xml`, `res/xml/`)
- Add `<uses-permission android:name="android.permission.INTERNET" />`.
- Add `res/xml/network_security_config.xml` permitting cleartext **only** to
  `10.0.2.2` and `localhost`; reference via
  `android:networkSecurityConfig="@xml/network_security_config"`.

### 7. Dependencies (`libs.versions.toml`, `app/build.gradle.kts`)
- `retrofit`, `converter-gson`, `okhttp-logging-interceptor`,
  `androidx-lifecycle-viewmodel-compose`.

## Error taxonomy (Extracting error panel)

| Cause | Detected as | Copy (non-advisory) |
|---|---|---|
| Vision not configured | HTTP 503 | "The reader isn't switched on yet. (Backend needs its API key.)" |
| Can't reach backend | IOException/timeout | "Couldn't reach the reader. Check that the service is running." |
| Bad/too-large image | HTTP 400/413/415 | "That photo didn't work — try another." |
| Nothing read | 200 + empty list | "We couldn't read any medicines. Try a clearer photo." |

All show **Retry** and **Back to camera**. No mock fallback.

## Testing / verification

- Build (Corretto 21), install on `Pixel_10`.
- With BE running **without** a key → capture/gallery → Extracting shows the 503
  error state + Retry. (Proves the wire + honest failure.)
- With BE running **with** a key → pick `IMG_5562` → real medicines render on
  Verify; confirm a non-daily/strength/duration flag shows an empty re-check box.
- Screenshot each state.

## Out of scope

- Real formulary autocomplete in the Verify edit dialog.
- Persisting results (Room); re-scan just overwrites the hoisted `meds`.
- DoctorShare switching off `MockData` (separate follow-up; still compiles).
- Auth/token on the request (endpoint is open in dev).
