# Capture: real camera + gallery upload — design

**Date:** 2026-07-22
**Status:** Approved (design), pending implementation
**Scope:** Android app (`android/`), Phase 2 UI wiring — first piece of "real behavior".

## Problem

`CaptureScreen` is currently a pure mock: a hand-drawn viewfinder over a fake
prescription, and tapping the shutter just calls `onCapture()` → navigates to
`ExtractingScreen`, which shows hardcoded demo text and ignores any image. There
is no camera, no gallery import, and no image flows anywhere.

We want the user to actually **take a photo** of their prescription **or upload
one from the gallery**, and see that real image on the "Reading your
prescription…" screen. Extraction itself stays mocked (hardcoded meds) — this
change is about image acquisition + plumbing, not the extraction engine.

## Decisions (locked)

- **Camera = system intent**, not CameraX. Tapping the shutter launches the
  phone's stock camera app via `ActivityResultContracts.TakePicture()`. Much
  less code; no CameraX dependency; **no runtime permissions** (we declare
  neither the `CAMERA` permission nor a camera `<uses-feature>`, so
  `ACTION_IMAGE_CAPTURE` needs no grant). Trade-off accepted: the branded
  viewfinder becomes a launch screen rather than the live camera surface.
- **Gallery = Android Photo Picker** (`ActivityResultContracts.PickVisualMedia`,
  `ImageOnly`). Returns a content URI directly; **no permission prompt**.
- **Show the real photo on `ExtractingScreen`** (replaces the mock paper). Thread
  the image URI forward through nav.
- **Image loading = Coil** (`coil-compose`). Handles EXIF rotation (camera
  photos are frequently rotated) and downsampling automatically; standard
  Android image loader. Chosen over hand-rolled `BitmapFactory` to avoid manual
  rotation handling and OOM risk on full-res photos.
- **Gallery button placement = beside the shutter** — a small gallery icon to
  the left of the shutter (camera-app convention).

## Architecture / flow

```
CaptureScreen
  ├─ shutter  → TakePicture(cameraOutputUri)     ──┐  on success/pick:
  └─ 🖼 icon  → PickVisualMedia(ImageOnly)        ──┤  onCapture(uri)
                                                    ▼
RxScanNav:  capturedUri = uri; nav.navigate(EXTRACTING)
                                                    ▼
ExtractingScreen(imageUri = capturedUri)  → renders real photo via Coil
```

### 1. `CaptureScreen.kt`
- Signature: `onCapture: () -> Unit` → **`onCapture: (Uri) -> Unit`**.
- Obtain `LocalContext`.
- **Camera output URI:** create an empty file under `cacheDir/captures/` (e.g.
  `rx_capture.jpg`) and wrap it with
  `FileProvider.getUriForFile(ctx, "${applicationId}.fileprovider", file)`.
  Remember it so the success callback can pass it to `onCapture`.
- **Camera launcher:** `rememberLauncherForActivityResult(TakePicture())` →
  `if (success) onCapture(cameraUri)`.
- **Gallery launcher:**
  `rememberLauncherForActivityResult(PickVisualMedia())` →
  `uri?.let { onCapture(it) }`.
- Shutter `clickable` → launches camera with the prepared URI.
- Add a small circular gallery icon button to the **left of the shutter**
  (`Icons` image/photo-library icon, tinted for the dark background) → launches
  the photo picker. Lay shutter + icon in a `Box`/`Row` so the shutter stays
  centered and the icon sits to its left (mirrors stock camera apps).
- Cancel/back from either launcher = no-op (stay on capture screen).

### 2. `RxScanNav.kt`
- Hoist `var capturedUri by remember { mutableStateOf<Uri?>(null) }` alongside
  the existing hoisted `phone`. Plain `remember` (not `rememberSaveable`) — a
  transient cache-file URI needn't survive process death for this UI pass.
- `CaptureScreen(onCapture = { capturedUri = it; nav.navigate(Routes.EXTRACTING) })`.
- `ExtractingScreen(imageUri = capturedUri, onDone = { … })`.
- `Today → Scan new` already routes to `CAPTURE`, so re-scans reuse this path
  automatically; the new capture overwrites `capturedUri`.

### 3. `ExtractingScreen.kt`
- Add param `imageUri: Uri? = null`.
- When `imageUri != null`: render it with Coil's `AsyncImage` in the same
  rounded "paper" slot (same size/clip/border), `ContentScale.Crop` or `Fit` to
  suit the frame. When `null`: keep the existing mock paper (preserves
  `@Preview` and defensive fallback).
- Timing / steps list unchanged.

### 4. `res/xml/file_paths.xml`
- Add `<cache-path name="captures" path="captures/" />` so the camera-output
  file is grantable to the stock camera app. FileProvider is already declared in
  `AndroidManifest.xml`.

### 5. Dependencies
- `gradle/libs.versions.toml`: add Coil version + `coil-compose` library entry.
- `app/build.gradle.kts`: add the `coil-compose` implementation dependency.

## Error handling / edge cases

- **User cancels camera or picker** → launcher returns `false`/`null` → no-op,
  stay on `CaptureScreen`. No crash, no forward nav.
- **Camera app unavailable** (rare on real devices; some emulators lack a camera
  app) → the intent may fail. Acceptable for this pass; the gallery path is the
  reliable route on the light AVD. (Not adding a fallback UI now.)
- **Large photo** → Coil downsamples to the display size; no manual OOM
  handling needed.
- **EXIF rotation** → handled by Coil.

## Testing / verification

- Build the debug APK (Corretto 21 — the `libs.versions.toml` JAVA_HOME in
  CLAUDE.md is stale; use `~/Library/Java/JavaVirtualMachines/corretto-21.0.11`).
- Install on the running `Pixel_10` emulator.
- **Gallery path** (reliable on emulator): Welcome → consent → capture → tap
  gallery icon → Android photo picker → choose an image → Extracting shows that
  real image → Verify. Screenshot each key step.
- **Camera path**: tap shutter → stock camera opens → capture → Extracting shows
  the photo. (If the AVD has no camera, note it and rely on gallery for the
  screenshot proof.)
- Confirm cancel = stays on capture screen.

## Out of scope (unchanged)

- Real extraction (meds stay mocked in `MockData`).
- Deleting the photo after reading (data-minimisation cleanup is a later phase;
  copy already says "discarded right after reading" — consistent, no cleanup
  wired yet).
- Honest-failure / unreadable-image state.
- CameraX live preview inside the viewfinder.
