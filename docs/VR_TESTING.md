# Moonlight Quest VR — Build & Test Plan

This document covers how to build the Quest VR flavor, sideload it to a Quest 2, and
manually verify the feature. It also states plainly what could and could not be
verified in the environment where this code was authored.

---

## 0. What was and wasn't verified during authoring

**Could NOT be built or run in the authoring environment**, because it had:
- no Android SDK and no Android NDK installed,
- no Gradle/Kotlin toolchain resolved,
- the `moonlight-common-c` git submodule not checked out,
- and, fundamentally, **no Quest hardware** (VR behavior can only be observed on a
  headset).

So the code here is **not compile-verified**. It was written against the current
public APIs of Moonlight (read directly from this repo) and the Meta Spatial SDK
(verified against Meta's official docs and the Meta-Spatial-SDK-Samples). What has
been checked is **cross-file consistency**: JNI-free integration seams, matching
method signatures against the real Moonlight classes, flavor isolation, and manifest
merge structure. See "Static self-review checklist" below.

**You must build on a configured machine and test on a Quest 2** to confirm runtime
behavior. Two things in particular are SDK-version-sensitive and may need a small
tweak once you compile against your installed Spatial SDK version (both are isolated
and commented in the code):
1. `VrPanelInput.hitInfoToUv()` — how `HitInfo` exposes the panel-local hit location.
2. The Spatial SDK Gradle plugin + dependency versions (see step 1).

---

## 1. Prerequisites

- Android Studio (latest) with Android SDK Platform 34 and NDK `27.0.12077973`.
- JDK 17.
- Check out the native submodule (required for any build of this repo):
  ```bash
  git submodule update --init --recursive
  ```
- A Meta Quest 2 in **Developer Mode** (enable via the Meta Horizon phone app:
  Devices → your headset → Developer Mode → On) with a developer account.
- `adb` (from platform-tools) and a USB-C cable, or `adb connect` over Wi-Fi.

### Version set (already applied)
The Meta Spatial SDK requires a coordinated toolchain. This repo is now pinned to the
version set from the Meta-Spatial-SDK-Samples. Verified: `./gradlew` resolves all three
plugins (AGP, Kotlin, Meta Spatial) and configures the project — the only remaining
failure in a headless env is the missing Android SDK location.

| Component | Version | Where it's set |
|---|---|---|
| Meta Spatial SDK | **0.13.1** | `ext.spatialSdkVersion` (root `build.gradle`) + `questImplementation` deps |
| Kotlin | **2.1.0** | `ext.kotlinVersion` (root `build.gradle`) |
| Android Gradle Plugin | **8.11.1** | root `build.gradle` classpath |
| Gradle wrapper | **9.4.1** | `gradle/wrapper/gradle-wrapper.properties` |
| JDK (to run Gradle) | **17** | your machine / `setup-and-build.ps1` |

These MUST move together — bumping the Spatial SDK alone fails because it needs the
newer AGP/Kotlin/Gradle. Note AGP/Gradle are project-wide, so the `mobile` flavor now
builds with AGP 8.11.1 too (previously 8.5.1); its output is otherwise unchanged.

To move to a newer Spatial SDK later, open that release's samples
`gradle/libs.versions.toml`, read its `agp` / `kotlin` versions, and update all four
rows above in lockstep.

---

## 2. Building

The project now has two flavor dimensions: `root`/`nonRoot` × `mobile`/`quest`
(the `root`+`quest` combination is filtered out). Notable variants:

| Variant task | What it is |
|---|---|
| `assembleNonRootMobileDebug` | The original phone/tablet/TV app. Unchanged. |
| `assembleNonRootQuestDebug` | The Quest VR build. |
| `assembleNonRootMobileRelease` | Original release app. |
| `assembleNonRootQuestRelease` | Quest VR release build. |

Build the Quest debug APK:
```bash
./gradlew assembleNonRootQuestDebug
```
APK output:
```
app/build/outputs/apk/nonRootQuest/debug/app-nonRoot-quest-debug.apk
```

Sanity-check that the original app is untouched:
```bash
./gradlew assembleNonRootMobileDebug
```
This must succeed **without** pulling any Spatial SDK dependency (they're scoped to
`questImplementation`). The Quest build has applicationId suffix `.quest`, so it
installs side-by-side with the phone build.

---

## 3. Installing to the Quest

```bash
adb devices                 # confirm the Quest is listed/authorized
adb install -r app/build/outputs/apk/nonRootQuest/debug/app-nonRoot-quest-debug.apk
```
The app appears in the Quest library under **Unknown Sources** → "Moonlight" (the
`-quest` versionName suffix distinguishes it).

Watch logs while testing (tag used throughout the VR code is `MoonlightVR`, plus
Moonlight's own `LimeLog`):
```bash
adb logcat -s MoonlightVR:V LimeLog:V AndroidRuntime:E
```

---

## 4. Manual test checklist (on Quest 2)

### A. Non-VR paths are unchanged (regression guard)
1. Launch the app. It opens the normal 2D Moonlight PC list on the Quest's flat panel.
2. Add/pair a PC exactly as on phone. **Expected:** identical behavior; all menus,
   dialogs, and Settings look and work as the original app.

### B. Enter VR streaming
3. Start streaming an app/game from the PC list.
   **Expected:** the view transitions into an immersive VR space — a **pitch-black
   void** with a single **flat video panel** floating ~2 m in front of you.
4. Confirm the streamed desktop/game is actually rendering on the panel (moving,
   not a black or frozen quad). This is the key check that the panel `Surface` →
   `MediaCodecDecoderRenderer` path works.
   *If the panel is black:* check `adb logcat` for decoder errors and confirm the
   `VideoSurfacePanelRegistration.surfaceConsumer` fired (add a log in
   `onVideoSurfaceAvailable`).

### C. Move the panel
5. Point a controller at the panel, hold the **grip** button, and move your hand.
   **Expected:** the panel follows your controller (Grabbable FACE); release to place.

### D. Resize the panel
6. Hold **A** (right controller) → panel grows; hold **X** (left controller) → panel
   shrinks. **Expected:** smooth uniform scaling, clamped (won't disappear or invert).
   *(Thumbstick resize is intentionally not wired in v1; see VrResizeSystem comments.)*

### E. Pointer → touch on the host
7. Point at the panel and pull the **trigger**.
   **Expected:** the host cursor jumps to the pointed location and a left-click is
   sent (press on squeeze, release on let-go) — same as tapping a touchscreen in the
   phone build. Drag with the trigger held to click-drag.
8. Press **B** or **Y**. **Expected:** a right-click on the host.

### F. Gamepad still forwarded
9. Connect an external Bluetooth gamepad to the Quest (or use a supported controller)
   and confirm game input reaches the host as before. **Expected:** unchanged gamepad
   forwarding (this path is identical to the phone build via `ControllerHandler`).

### G. Exit / teardown
10. Quit the stream (host-side or disconnect). **Expected:** the VR activity finishes
    cleanly, the connection stops, and you return to the 2D UI without a crash.

---

## 5. Static self-review checklist (done during authoring)

- [x] `mobile` flavor references **no** VR classes; Spatial SDK is `questImplementation`
      only → the original app is dependency-clean.
- [x] Streaming stack is reused verbatim: `MediaCodecDecoderRenderer` constructor,
      `StreamConfiguration.Builder`, `NvConnection.start(audio, decoder, listener)`,
      `ControllerHandler`, `NvConnectionListener` callbacks — all signatures matched
      against the real classes in `app/src/main`.
- [x] Video seam: panel `Surface` → `VrSurfaceHolder` → `decoderRenderer.setRenderTarget()`
      — the decoder only calls `getSurface()` on its render target.
- [x] `PerfOverlayListener` passed as a no-op (not null) because the decoder calls it
      unconditionally when the perf-overlay pref is enabled.
- [x] Launch routing changes exactly one shared line in `ServerHelper` via the
      per-flavor `VrLaunch.streamActivityClass()`; intent extras unchanged.
- [x] `getAttachedControllerMask()` (short) widened to `int` for the builder.
- [x] Connection start marshalled to the main thread (matches Game.surfaceChanged).
- [x] Manifest overlay merges onto `src/main`; `VrGameActivity` is VR-categorized and
      is **not** a LAUNCHER (app still opens the 2D UI first).

## 6. Known limitations (v1)
- SDR only (no HDR) in VR; simplifies the decoder setup vs. the flat build.
- No performance overlay / on-screen keyboard in VR yet.
- Resize is on face buttons (A/X), not the thumbstick (documented, easy to extend).
- Panel starts at a fixed size/pose; no persistence of last position/scale.
