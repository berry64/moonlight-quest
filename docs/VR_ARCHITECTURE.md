# Moonlight Quest VR — Architecture (Meta Spatial SDK)

This document is the source of truth for the Quest VR build. It uses the **Meta
Spatial SDK panel** approach (NOT native OpenXR), which is Meta's recommended path
for putting an existing 2D Android app into an immersive space.

## Goal / scope (as agreed with the user)

- The streamed video is a **flat quad panel** floating in a **pitch-black void**.
- The user can **move** the panel (grab with controller) and **resize** it.
- Controllers act as a laser pointer; **trigger = touch/click** on the panel,
  forwarded to the host via Moonlight's existing input path. Visible pointer
  indicator is provided by the Spatial SDK's built-in controller ray + cursor.
- The gamepad continues to be forwarded to the host exactly as today.
- **Every other screen stays byte-for-byte the original 2D Android UI** (PcView,
  AppView, pairing, StreamSettings, dialogs). This is guaranteed by isolating all VR
  code in a separate `quest` product flavor; the default `mobile` flavor is untouched.

## Why Spatial SDK panels (the key de-risking finding)

`VideoSurfacePanelRegistration` provides a raw Android `Surface` via a
`surfaceConsumer = { entity, surface -> ... }` callback and *renders directly to the
panel surface, bypassing the Android View system* (per Meta docs: "maximum
performance ... renders media content directly to the panel surface, bypassing the
Android View system"). 

This is the same seam Moonlight already uses: `MediaCodecDecoderRenderer` decodes
into whatever `Surface` you hand it via `setRenderTarget(SurfaceHolder)`. So we take
the panel's `Surface`, wrap it in a trivial `SurfaceHolder`, and hand it to the
existing decoder. **Moonlight's entire connection/decoder/audio/controller stack is
reused unchanged.** There is NO SurfaceView-composites-to-black problem because we
never capture a view hierarchy — we get a real hardware Surface.

## Build structure

New Gradle flavor dimension `device` = { `mobile` (default), `quest` }.

- Existing `root` dimension preserved (`root`/`nonRoot`). A `variantFilter` drops the
  nonsensical `root + quest` combination (root is `maxSdk 25`; Quest needs Android 10+).
- `mobile` flavor: current behavior, byte-for-byte. `BuildConfig.VR_BUILD == false`.
  No Spatial SDK dependency, no Kotlin sources compiled into it beyond what's shared.
- `quest` flavor: `BuildConfig.VR_BUILD == true`; adds Spatial SDK deps + Kotlin,
  a VR manifest, and the immersive `VrGameActivity`.

Source sets:
- `src/main` — shared Java. Existing streaming code. One tiny `VR_BUILD`-gated hook in
  `ServerHelper.createStartIntent(...)` so the quest flavor launches `VrGameActivity`
  instead of `Game` (see "Launch routing" below).
- `src/quest/java/com/limelight/vr/*.kt` — all VR code (Kotlin, Spatial SDK).
- `src/quest/AndroidManifest.xml` — immersive VR activity + Quest features (merged).
- `src/mobile/` — nothing VR (mobile never references VR classes).

Kotlin is only needed for the quest flavor, but the Kotlin Gradle plugin applies
project-wide; that's fine — it does not change the mobile output (no Kotlin sources
in mobile beyond none). AGP/Gradle/Kotlin versions must be compatible with Spatial SDK.

## Launch routing (the ONE shared-code change)

`ServerHelper.createStartIntent()` currently does `new Intent(parent, Game.class)`.
We change ONLY the target class, gated by `BuildConfig.VR_BUILD`:

```java
Class<?> target = BuildConfig.VR_BUILD ? VR_GAME_ACTIVITY : Game.class;
Intent intent = new Intent(parent, target);
```

Because `src/mobile` and `src/quest` can't both define the same class, we resolve the
class by name reflectively OR (cleaner) via a tiny indirection: a shared interface
constant `GameLauncher.STREAM_ACTIVITY` provided per-flavor. Chosen approach: a
per-flavor `com.limelight.vr.VrLaunch` helper with a static `streamActivityClass()`:
- `src/mobile/.../VrLaunch.java` returns `com.limelight.Game.class`.
- `src/quest/.../VrLaunch.java` returns `com.limelight.vr.VrGameActivity.class`.
`ServerHelper` calls `VrLaunch.streamActivityClass()`. This keeps `ServerHelper`
flavor-agnostic and adds zero VR imports to shared code. Both `Game` and
`VrGameActivity` read the SAME intent extras (Game.EXTRA_*), so the intent building is
unchanged.

## VrGameActivity — reuses Moonlight's streaming building blocks

`src/quest/java/com/limelight/vr/VrGameActivity.kt` extends Spatial SDK's
`AppSystemActivity`. It replicates the streaming setup that `Game.java` performs (same
sequence: read PreferenceConfiguration, MediaCodecHelper.initialize, build
MediaCodecDecoderRenderer, build StreamConfiguration, create NvConnection,
ControllerHandler), but instead of a `StreamView` it renders into the panel Surface.

Responsibilities:
1. `registerFeatures()` → `VRFeature(this)` (+ debug features).
2. `registerPanels()` → one `VideoSurfacePanelRegistration` for the video quad. Its
   `surfaceConsumer` receives the panel `Surface`; we wrap it in `VrSurfaceHolder`,
   call `decoderRenderer.setRenderTarget(holder)` and `conn.start(...)` — mirroring
   `Game.surfaceChanged`. Shape = `QuadShapeOptions` sized to the video aspect ratio;
   display = `PixelDisplayOptions(width, height)` at the stream resolution.
3. `onSceneReady()`:
   - Pitch-black void: NO skybox entity; `scene.setLightingEnvironment(ambient=0,
     sun=0, ...)`; set the reference space and a black clear color. No environment mesh.
   - Create the video panel `Entity.createPanelEntity(id, Transform(placeInFront),
     Grabbable(true, GrabbableType.PIVOT_Y))` → grab-to-move for free.
   - Add an `InputListener` on the panel entity for touch mapping (below).
   - Register a `SystemBase` (`VrResizeSystem`) that reads the controller thumbstick
     each frame and scales the panel Transform (resize).
4. Input → host touch: `InputListener.onInput(receiver, hitInfo, source, changed,
   clicked, downTime)`. `hitInfo` gives the hit point in panel-local space; convert to
   normalized [0,1] (origin top-left, y down) and forward to the existing host input
   path (`conn.sendMousePosition` + `conn.sendMouseButtonDown/Up` on trigger edges),
   mirroring `AbsoluteTouchContext`. Thumbstick-driven resize is separate from touch.
5. Gamepad: build a `ControllerHandler` exactly as `Game.java` does and forward Quest
   controller `KeyEvent`/`MotionEvent` to it (dispatchGenericMotionEvent /
   dispatchKeyEvent are still delivered to an Activity). The gamepad path is unchanged.
6. Lifecycle: implement `NvConnectionListener` (stage callbacks, rumble, HDR, etc.)
   like `Game.java` — minimal versions (toasts/logs; rumble via controller if easy).
   On connection termination or user exit → finish the activity.

### VrSurfaceHolder
Wraps a single `android.view.Surface` from the panel as a `SurfaceHolder`. The decoder
only calls `getSurface()` (and Game-style `setFrameRate` isn't required). Other
`SurfaceHolder` methods return sensible defaults / throw `UnsupportedOperationException`
with comments. `getSurfaceFrame()` reports the video resolution rect. We don't need the
`SurfaceHolder.Callback` dance because we drive `setRenderTarget` + `conn.start`
directly from `surfaceConsumer`.

## Coordinate convention
- Host input uses normalized [0,1], origin top-left, x right, y down (matches
  `AbsoluteTouchContext.updatePosition` and Android view coords).
- `conn.sendMousePosition((short)(u*W),(short)(v*H),(short)W,(short)H)` where W,H are
  the stream resolution.

## Manifest (quest flavor: src/quest/AndroidManifest.xml, merged with main)
- `horizonos:uses-horizonos-sdk` min/target (e.g. 69).
- `<uses-feature android:name="android.hardware.vr.headtracking" android:required="true"/>`.
- `<uses-feature android:glEsVersion="0x00030001"/>`.
- `<uses-native-library android:name="libossdk.oculus.so" android:required="true"/>`.
- `<meta-data com.oculus.supportedDevices = quest2|quest3|questpro/>`.
- `<meta-data com.oculus.vr.focusaware = true/>`.
- Declare `com.limelight.vr.VrGameActivity` with intent-filter category
  `com.oculus.intent.category.VR` (immersive). It is NOT a LAUNCHER — the app still
  launches into the normal 2D `PcView`; VR starts only when a stream begins.

## Gradle / version compatibility (RISK — must be verified on a real toolchain)
- Spatial SDK `0.12.0` (or latest) via `com.meta.spatial:*` and the
  `com.meta.spatial.plugin` Gradle plugin.
- Spatial SDK samples use AGP 8.11.1 / Gradle 9.4 / Kotlin 2.1.0 / JDK 17. This project
  currently uses AGP 8.5.1 / Gradle 8.7. The quest flavor likely needs AGP/Gradle
  bumps. We document this rather than silently bumping the whole project; the exact
  versions must be reconciled against the installed toolchain (none is available in
  this authoring environment).
- Spatial SDK deps are added with `questImplementation` so mobile stays clean. The
  Kotlin plugin and Spatial Gradle plugin apply project-wide.
- `android.buildFeatures.prefab`/compose may be needed depending on panel UI approach;
  we use `VideoSurfacePanelRegistration` (Surface, not Compose) to minimize deps.

## Files delivered
- `app/build.gradle` — device flavor dimension, variantFilter, Kotlin + Spatial plugin,
  questImplementation Spatial deps, VR_BUILD BuildConfig fields.
- `app/src/quest/AndroidManifest.xml` — immersive VR activity + Quest features.
- `app/src/quest/java/com/limelight/vr/VrGameActivity.kt` — immersive streaming activity.
- `app/src/quest/java/com/limelight/vr/VrSurfaceHolder.kt` — Surface→SurfaceHolder shim.
- `app/src/quest/java/com/limelight/vr/VrResizeSystem.kt` — thumbstick resize system.
- `app/src/quest/java/com/limelight/vr/VrLaunch.java` — returns VrGameActivity.class.
- `app/src/mobile/java/com/limelight/vr/VrLaunch.java` — returns Game.class (stub).
- `app/src/main/.../ServerHelper.java` — one-line change to use VrLaunch.streamActivityClass().
- `docs/VR_TESTING.md` — build + on-device (Quest 2) test plan and what can/can't be verified.

## Honest limitations (authoring environment)
- No Android SDK/NDK/Gradle/Kotlin toolchain is installed here, and the
  `moonlight-common-c` submodule is not checked out, so the project CANNOT be compiled
  or run in this environment. Functional testing additionally requires physical Quest 2
  hardware. Deliverables are complete, API-consistent code + an exact test plan; the
  user must build on a configured machine and test on-device.
</content>
