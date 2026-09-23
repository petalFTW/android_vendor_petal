# Petal AgeVer

A petalOS privacy camera: instead of your real front camera, age-verification
apps get a **3D avatar on a flat white stage**. The real camera is only ever used
as an *input* to drive the avatar (AR mode); its pixels are analysed in memory
and discarded — never displayed and never written to disk.

Module: `PetalAgeVer` · Package: `org.petalos.agever` · Platform-signed,
privileged, `system_ext` app.

---

## What is implemented (phases 1 + 2)

- **The front camera, replaced.** `ProviderFrameSink` registers the avatar feed
  with petalOS' virtual camera provider as an extra
  `LENS_FACING_FRONT` camera on the default device *and* arms CameraService's
  petal front-camera override, so while the stage is live every front-camera
  open — including apps that just pick "the" front camera — is served the
  avatar feed instead of the real sensor. It appears and disappears with the
  QS tile, no reboot required.
- **Filament 3D stage** rendering a glTF avatar on a white background
  (`AvatarStage`). Renders into *any* `Surface`, so the same pipeline drives the
  in-app preview and the camera HAL input surface.
- **Two control modes** (`ControlState`, `ControlMode`):
  - `AR` — real front camera → MediaPipe `FaceLandmarker` → head pose +
    ARKit blendshapes (`MediaPipeFaceTracker`).
  - `JOYSTICK` — a floating overlay joystick (`JoystickOverlay`,
    `JoystickView`) that turns the avatar while another app is on screen.
  - `IDLE` — no input; optional subtle autonomous motion.
- **Avatar rig** (`AvatarRig`) that prefers glTF morph targets and otherwise
  drives named nodes (`Head`, `Body`, `EyeL`, `EyeR`, `Mouth`).
- **QS tile** (`StageTile`) to arm/disarm, **foreground service**
  (`StageService`), notification with stop action, boot restore.
- **Import avatars** (`.glb`) from the picker; bundled default avatar.
- **Pluggable output** (`FrameSink`): `ProviderFrameSink` (live camera) with
  transparent fallback to `PreviewFrameSink`.

Verified on `petal_macanc-bp4a-user`: `m virtual_camera PetalAgeVer` and
`m selinux_policy` both succeed; the provider binary, the APK and the
`system_ext` SELinux rule are installed.

---

## How the injection works (phase 2)

The AOSP virtual camera provider (`frameworks/av/services/camera/virtualcamera`)
is enabled on petal builds and reused as-is, with two small patches. The key
insight is that `CameraService` treats a camera as a **normal default-device
camera** when its `ANDROID_INFO_DEVICE_ID` is `0`/absent, and
`VirtualCameraService.registerCamera(token, config, deviceId)` already accepts a
`deviceId`. So:

1. `ProviderFrameSink` first arms the override with an *empty* camera id
   (`setPetalFrontCameraOverride(true, "")`). CameraService then fails closed:
   from that instant no app can open any real front camera, so there is no
   window in which real pixels could flow.
2. `ProviderFrameSink` waits for the lazily-started `virtual_camera` service and
   calls `registerCamera(token, config, 0)` with `LENS_FACING_FRONT` and
   `RGBA_8888` input streams.
3. `CameraProviderManager` already registers a notification listener for the
   `ICameraProvider/virtual/0` instance, so the camera appears live via
   `cameraDeviceStatusChange` — no reboot.
4. The sink re-arms the override with the avatar camera's real id (`v0_*`,
   returned by `IVirtualCameraService.getCameraId`). From then on
   `CameraService.resolvePetalFrontCameraLocked` rewrites every front-camera
   `connect`/`getCameraInfo`/`getCameraCharacteristics` to the avatar camera.
   The app's own UID is exempt, so AR face-tracking can still open the real
   camera as an input.
5. When a client opens the (remapped) camera, the provider calls
   `IVirtualCameraCallback.onStreamConfigured(...)` with a producer `Surface`;
   the app renders Filament straight into it.
6. On stop the override is disarmed first (real camera restored), then the
   camera is unregistered and disappears. The armed state persists in
   `persist.petal.camera_override`, so a crash or reboot leaves the front
   camera *blocked* (fail-closed) until the app re-arms the replacement.

Three ROM-side requirements are involved: the two `frameworks/av` provider
patches below, and petalOS' CameraService petal hooks
(`ICameraService.setPetalFrontCameraOverride` +
`resolvePetalFrontCameraLocked`, shipped in petal's `frameworks/av`). The app
needs the platform `CAMERA_INJECT_EXTERNAL_CAMERA` signature permission
(granted automatically: the app is platform-signed) for the override call, and
its own `MANAGE_VIRTUAL_CAMERA` signature permission for the provider calls.

Two patches to `frameworks/av` (provider side):
- the provider accepts PetalAgeVer's own signature permission (the stock
  `CREATE_VIRTUAL_DEVICE` is `internal|role` and not grantable);
- the input surface can default to `RGBA_8888` so Filament can draw into it
  (YUV stays the default for the VDM path).

Full details, the patch files and rollback steps are in `rom/README.md`. The
older plan of writing a brand-new provider HAL from scratch proved unnecessary.

> Note: the companion `android.companion.virtual.camera` SystemApi is *not* used
> because it is scoped to a virtual device context and is invisible to apps on
> the default device.

---

## ROM integration

1. Copy this tree to `vendor/petal/apps/PetalAgeVer`.

2. Apply the provider patch, add the product-makefile block, drop in the
   SELinux rule and copy the app — see `rom/README.md` for the exact steps and
   the rollback procedure. The block that goes in every petal product is:

   ```make
   PRODUCT_PACKAGES += \
       PetalAgeVer \
       virtual_camera
   $(call soong_config_set,vdm,virtual_camera_service_enabled,true)
   SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/petal/sepolicy
   ```

3. Build (verified with the macanc test target):

   ```sh
   source build/envsetup.sh
   lunch petal_macanc-bp4a-user
   m PetalAgeVer virtual_camera
   m selinux_policy          # validates the rule + neverallows
   ```

4. First-run appops for the floating joystick (system-priv apps are usually
   auto-granted; grant manually if not):

   ```sh
   appops set org.petalos.agever SYSTEM_ALERT_WINDOW allow
   ```

5. If SELinux is put in enforcing mode on a build that lacks
   `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS`, the app falls back to preview only (it
   never crashes or loops) and logs `binder call`/`find` denials under the
   `platform_app` domain.

### Dependencies

All vendored under `libs/` (no Gradle/Maven at build time):

- `filament-android-1.76.1.aar`, `gltfio-android-1.76.1.aar` (Apache-2.0) —
  repacked arm64-v8a only.
- MediaPipe `tasks-vision.aar` / `tasks-core.aar` + transport/protobuf/flogger
  runtime (Apache-2.0).
- `assets/face_landmarker.task` (Apache-2.0).

The module names are prefixed `agever-` so they do not collide with the
identically-sourced modules in `vendor/petal/apps/PetalDepth`.

---

## Avatar asset

The bundled avatar (`assets/avatars/default.glb`) is generated, not sourced, so
it is clean to redistribute. Regenerate or tweak it with:

```sh
python3 tools/make_avatar.py            # writes assets/avatars/default.glb
```

It is a stylized low-poly figure with the named nodes the rig drives. Any glTF
2.0 / GLB avatar can be imported at runtime; avatars that ship ARKit morph
targets (`jawOpen`, `eyeBlinkLeft`, …) are driven by weights instead of nodes.

---

## Layout

```
PetalAgeVer/
  Android.bp                     # android_app "PetalAgeVer" + vendored prebuilts
  AndroidManifest.xml
  org.petalos.agever.xml         # privapp allowlist
  proguard.flags
  assets/
    avatars/default.glb          # generated
    face_landmarker.task
  libs/                          # vendored AARs / jars (arm64)
  licenses/
  res/                           # dark petalOS theme, activity + overlay layouts
  src/org/petalos/agever/
    PetalAgeVerApp.java
    Prefs.java
    MainActivity.java
    StageService.java            # foreground session
    StageTile.java               # QS tile
    BootReceiver.java
    core/                        # ControlMode, ControlState, ControlSnapshot
    camera/                      # FaceTracker, FaceData, MediaPipeFaceTracker
    render/                      # AvatarStage, AvatarRig, TransformUtils
    output/                      # FrameSink, PreviewFrameSink, ProviderFrameSink
    ui/                          # JoystickView, JoystickOverlay
  tools/make_avatar.py
```

## Privacy properties

- No real-camera pixels are displayed, persisted, or transmitted — only head
  pose and a handful of blendshape scalars leave the analyser.
- The output stage has no text, no background detail and no identity: a flat
  white frame with a generic avatar.
- No network permission is requested.

## Opening the controls

Petal AgeVer has no launcher entry. Add **AgeVer camera** in the Quick Settings
tile editor, then hold the tile to open the app. Tapping the tile continues to
start or stop the camera. The existing Settings entry remains available.

`MainActivity` handles `android.service.quicksettings.action.QS_TILE_PREFERENCES`
with the DEFAULT category, which SystemUI resolves for a custom tile long press.
The application icon resource remains available for the tile, notification and
Settings entry; only launcher discovery is removed.

The controls follow the system light/dark theme, with Rubik typography, rounded
surfaces and blue accents. Theme changes recreate the activity; preview observers
and service bindings are released when the screen stops.

Redesign validation (22 September 2026): AAPT2 resource compilation/linking and
compilation of every app Java source passed against the existing platform build
dependencies, using isolated `/tmp` outputs. Manifest checks confirm no LAUNCHER
category, an exported tile-preferences activity and the protected tile service.
All existing main-screen control IDs are retained. Text and primary-action
contrast checks pass in both themes. A full ROM build was already holding the
shared output lock; full APK assembly and on-device UI/long-press validation
remain pending. No device was connected and no APK was installed.
