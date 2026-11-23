# Leica Frame Patcher (LSPOSED)

Companion LSPOSED/Xposed module for the Nezha Leica Camera Enabler Magisk module.
The Magisk module sets the Leica system props; this module patches the app behaviour
that props alone cannot reach.

Original author: mexusbg. Current maintainer: qfxlhh.

## What it fixes

1. **Leica frames rejected after capture** — mediaeditor `2.10.43.1.4` (and the earlier
   `2.10.43.0.6`) rejects camera-produced Leica templates because required photo fields
   fail the common requirement check (`x60.c1.a(...)` / `w60.x0.a(...)`) used by the
   gallery watermark template validation.
   Nezha photos are classified as non-Leica, producing *"unrecognized shooting
   parameters"* when the same frame was available in Camera. The error result (`f70.a$b`) is replaced
   with the existing success singleton (`f70.a$a`) so all Leica entry points agree.
   Validation and name replacement are limited to photos carrying Xiaomi 17 Ultra
   (`17 Ultra`/`nezha`) camera Make/Model metadata and `DateTimeOriginal`. Screenshots,
   imported images, and photos from other device models keep the stock behavior and name.
   The watermark config's 2000 ms network-time request is also replaced with the
   device clock; other network-time callers remain unchanged. This reduces online
   watermark loading time while retaining cached cloud config and resource download.

2. **Leica camera-island ring settings** — the Leica/Leitz variant has a control
   ring on the camera island; nezha does not, but the Leica props make its settings
   appear. Removed in two places (the Photography-Kit "Camera Grip" is a *separate*
   feature and is deliberately left working):
   - *In-camera* (`com.android.camera`): under the Leica theme (LCC),
     `MiThemeOperationCommonLC.supportHandleRing()` hard-returns `true`;
     `CameraCommonPreferenceFragment.addCurrentPreferences` (~line 240) gates the
     whole "Camera ring" block on it, as do the on-screen guide and gesture handler.
     Forced to `false`. Real (non-obfuscated) MiuiCamera name — stable.
   - *System Settings* (`com.android.settings`): a separate "Camera ring" header
     (`R.id.camera_mr_settings`, launching
     `com.miui.securitycore/…GestureShortcutCameraMrActivity`) is shown when
     `SettingsFeatures.SUPPORT_CAMERA_MR_FUNCTION` is true, which is set once from
     `miui.hardware.input.InputFeature.supportCameraMRFunction()`. The hook forces
     that method to `false` in the Settings process (before `SettingsFeatures`'
     static init runs), so `MiuiSettings` drops the header. Real framework name —
     stable. Does **not** touch `com.android.settings.cameragrip` (the Photography Kit).

3. **Watermark device name** — shows **"XIAOMI 17 Ultra by Leica"** (brand "XIAOMI"
   in the logo font, from the frame's own brand line) in both apps, replacing the
   stock "Leitzphone Powered by Xiaomi":
   - *Gallery* (`com.miui.mediaeditor`): the model line is produced by
     `o80.h.o(...)` in mediaeditor `2.10.43.0.6` and is forced to `WATERMARK_DEV`.
     Obfuscated names are version-specific — see the re-mapping note below.
   - *Camera* (`com.android.camera`): the live-shot watermark text comes from a family
     of provider classes (subclasses of `C15705`) whose `mo26211d()` returns a
     `SparseArray<String[]>` = `[manufacturer, device]`. Both the class and method
     names are **non-ASCII obfuscated and shift every build**, so they're located at
     runtime with **DexKit** (shape: `SparseArray`-returning, zero-arg, with the
     `SparseArray<String[]>` signature annotation) — exactly as HyperCeiler's
     `camera/CustomWatermark` does. The device slot is set to `WATERMARK_DEV`
     (`"17 Ultra by Leica"`); the frame prepends its own "XIAOMI" brand line.

   To change the text, edit `WATERMARK_DEV` in `HookEntry.java`
   and rebuild.

## Build

Open `lsposed-module/` in Android Studio, or from a shell (the Gradle wrapper is
committed):

```sh
cd lsposed-module
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed → installable).

This is a **modern Xposed module** (libxposed API 100+): it extends `XposedModule`,
hooks via the `hook(method).intercept(chain -> …)` interceptor API, and is configured
by `app/src/main/resources/META-INF/xposed/{module.prop,java_init.list,scope.list}`
(not the legacy manifest meta-data). **Requires an LSPOSED that supports API 100+.**

Dependencies: the API is `compileOnly 'io.github.libxposed:api:102.0.0'` (Maven
Central, provided by LSPOSED at runtime — not bundled); **DexKit**
(`org.luckypray:dexkit`) *is* bundled (native lib, ~4 MB APK) and powers the camera
watermark hook. libxposed provides no `XposedHelpers`, so reflection is done by hand.

## Install

1. Install the APK.
2. In the LSPOSED manager: enable the module, tick scope **Gallery editor**
   (`com.miui.mediaeditor`), **Camera** (`com.android.camera`), and **Settings**
   (`com.android.settings`). (Recommended scope is declared in `scope.list`.)
3. **Force-stop** all three (or reboot) so the hooks load. Check the LSPosed log
   for `gallery requirement hook active`, `gallery watermark name override active`,
   and `watermark network-time hook active`.
4. Verify: a Leica frame selected in Camera remains editable after reopening the
   photo in Gallery; the "Camera ring" entry is gone from both the camera settings and system Settings;
   and the watermark reads "XIAOMI 17 Ultra by Leica" (camera + gallery).

## If a HyperOS update breaks it

The gallery hooks resolve their targets by **shape**, so a rebuilt mediaeditor usually only
needs the outer class names refreshed. Current mappings:

| mediaeditor | requirement check | watermark name | network time |
|-------------|-------------------|----------------|--------------|
| 2.10.43.1.4 (versionCode 210994314) | `x60.c1.a(Watermark, String, ExifReader, WatermarkGroup)` → `f70.a` | `p80.i.o(ExifReader, WatermarkGroup, boolean)` | `ba0.a.a(long)` |
| 2.10.43.0.6 (versionCode 210430006) | `w60.x0.a(...)` → `e70.a` | `o80.h.o(...)` | `aa0.a.a(long)` |

The arrays `WM_VERIFY_CLASSES`, `WM_NAME_CLASSES` and `NETWORK_TIME_CLASSES` in
`HookEntry.java` list the known names newest-first; add the new name at the front when an
update rewrites them again. The requirement hook derives the result type and its
success/error variants from the matched method's return type, so only the outer class name
is version-specific. The EXIF reader is matched structurally (`String → String`), not by
name. Stable log markers at runtime:

- `gallery requirement hook active on …` (plus `result=` / `success=`)
- `gallery watermark name override active via …`
- `watermark network-time hook active on …`

If one disappears from logcat after an update, that lookup missed and the corresponding
class name needs refreshing.
