package io.github.mexusbg.leicaframes;

import android.util.Log;
import android.util.SparseArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.AnnotationMatcher;
import org.luckypray.dexkit.query.matchers.AnnotationsMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Modern (libxposed API 100+) Xposed module for the Xiaomi 17 Ultra (nezha) Leica tweaks.
 *
 * <ul>
 *   <li><b>Frames</b> — keeps camera-produced Leica frames eligible for editing in
 *       the gallery, even though nezha photos classify as xiaomi_series.</li>
 *   <li><b>Ring</b> — hides the Leica camera-island "Camera ring" settings (in the camera
 *       and in system Settings); the Photography-Kit "Camera Grip" is left working.</li>
 *   <li><b>Watermark</b> — renames the device name to "XIAOMI 17 Ultra by Leica" in the
 *       camera and the gallery editor.</li>
 * </ul>
 *
 * Hooks use the interceptor-chain API: {@code hook(method).intercept(chain -> result)}.
 * Returning a value without calling {@code chain.proceed()} replaces the result and skips
 * the original. libxposed provides no XposedHelpers, so reflection is done by hand below.
 */
public class HookEntry extends XposedModule {

    static final String TAG = "LeicaFramePatcher";

    private static final String PKG_MEDIAEDITOR = "com.miui.mediaeditor";
    private static final String PKG_CAMERA = "com.android.camera";
    private static final String PKG_SETTINGS = "com.android.settings";

    // MiuiCamera Leica-theme (LCC) operation. Real (non-obfuscated) name.
    private static final String CLS_THEME_LC =
            "com.android.camera2.compat.theme.custom.lc.MiThemeOperationCommonLC";
    // System Settings "Camera ring" (camera_mr) gate. Real framework name.
    private static final String CLS_INPUT_FEATURE = "miui.hardware.input.InputFeature";

    // mediaeditor obfuscated names, newest first. Xiaomi re-obfuscates the whole app on
    // every gallery update, so each lookup tries these in order and resolves the result
    // shape structurally from whatever class matched.
    //   2.10.43.1.4 (versionCode 210994314) -> x60.c1 / p80.i / ba0.a
    //   2.10.43.0.6 (versionCode 210430006) -> w60.x0 / o80.h / aa0.a
    private static final String[] WM_VERIFY_CLASSES = {"x60.c1", "w60.x0"};
    private static final String[] WM_NAME_CLASSES = {"p80.i", "o80.h"};
    private static final String[] NETWORK_TIME_CLASSES = {"ba0.a", "aa0.a"};

    // Watermark device name. The frame renders a brand line ("XIAOMI", logo font) ahead
    // of the device text, so the device slot omits the leading "Xiaomi".
    private static final String WATERMARK_MFR = "";
    private static final String WATERMARK_DEV = "17 Ultra by Leica";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader cl = param.getDefaultClassLoader();
        if (cl == null) return;

        if (PKG_MEDIAEDITOR.equals(pkg)) {
            safe("gallery watermark-requirements", () -> hookGalleryWatermarkRequirements(cl));
            safe("gallery watermark-name", () -> hookGalleryWatermarkName(cl));
            safe("watermark network-time", () -> hookWatermarkNetworkTime(cl));
        } else if (PKG_CAMERA.equals(pkg)) {
            safe("ring", () -> hookHideHandleRing(cl));
            String apk = param.getApplicationInfo() != null ? param.getApplicationInfo().sourceDir : null;
            if (apk != null) {
                final String apkPath = apk;
                safe("camera watermark-name", () -> hookCameraWatermarkName(cl, apkPath));
            }
        } else if (PKG_SETTINGS.equals(pkg)) {
            safe("camera-ring entry", () -> hookHideCameraRingEntry(cl));
        }
    }

    private interface HookInstall {
        void run() throws Throwable;
    }

    private void safe(String what, HookInstall install) {
        try {
            install.run();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to install " + what + " hook: " + t);
        }
    }

    // ---------------------------------------------------------------- ring (camera) -----

    /**
     * Hide the in-camera control-ring UI by forcing {@code supportHandleRing()} to false.
     * One method gates the settings entry, the on-screen guide, and the gesture handler.
     */
    private void hookHideHandleRing(ClassLoader cl) {
        Class<?> theme = findClass(CLS_THEME_LC, cl);
        if (theme == null) {
            log(Log.WARN, TAG, CLS_THEME_LC + " not found — ring hook skipped.");
            return;
        }
        Method m = findMethod(theme, "supportHandleRing", 0);
        if (m == null) {
            log(Log.WARN, TAG, "supportHandleRing() not found — ring hook skipped.");
            return;
        }
        hook(m).intercept(chain -> Boolean.FALSE);
        log(Log.INFO, TAG, "camera control-ring UI hidden (supportHandleRing=false).");
    }

    // ------------------------------------------------------------- ring (settings) ------

    /**
     * Hide the system-Settings "Camera ring" (camera_mr) entry by forcing
     * {@code miui.hardware.input.InputFeature.supportCameraMRFunction()} to false.
     * Runs before SettingsFeatures' static init, so the header is dropped. Does not touch
     * the Photography-Kit "Camera Grip" (a separate feature).
     */
    private void hookHideCameraRingEntry(ClassLoader cl) {
        Class<?> inputFeature = findClass(CLS_INPUT_FEATURE, cl);
        if (inputFeature == null) {
            log(Log.WARN, TAG, CLS_INPUT_FEATURE + " not found — camera-ring entry hook skipped.");
            return;
        }
        Method m = findMethod(inputFeature, "supportCameraMRFunction", 0);
        if (m == null) {
            log(Log.WARN, TAG, "supportCameraMRFunction() not found — camera-ring entry hook skipped.");
            return;
        }
        hook(m).intercept(chain -> Boolean.FALSE);
        log(Log.INFO, TAG, "system-settings Camera ring entry hidden (supportCameraMRFunction=false).");
    }

    // ------------------------------------------------------- watermark name (gallery) ---

    /**
     * Gallery watermark device name. Located by shape: a static method returning String
     * with three parameters whose first parameter is the EXIF reader (a class declaring
     * {@code String get(String)}). The obfuscated class/method names change every update.
     */
    private void hookGalleryWatermarkName(ClassLoader cl) {
        Class<?> nameHelper = null;
        Method nameMethod = null;
        for (String candidate : WM_NAME_CLASSES) {
            Class<?> cls = findClass(candidate, cl);
            if (cls == null) continue;
            nameMethod = findStaticStringMethod(cls, "o", 3);
            if (nameMethod == null) nameMethod = findStaticStringWithExif(cls, 3);
            if (nameMethod != null) {
                nameHelper = cls;
                break;
            }
        }
        if (nameMethod == null) {
            log(Log.WARN, TAG, "gallery watermark-name method not found in "
                    + java.util.Arrays.toString(WM_NAME_CLASSES) + " — skipped.");
            return;
        }

        Method exifGetter = findStringGetter(nameMethod.getParameterTypes()[0]);
        if (exifGetter == null) {
            log(Log.WARN, TAG, "gallery watermark-name EXIF getter not found — skipped.");
            return;
        }

        hook(nameMethod).intercept(chain ->
                isTargetCameraPhoto(chain.getArg(0), exifGetter)
                        ? WATERMARK_DEV
                        : chain.proceed());
        log(Log.INFO, TAG, "gallery watermark name override active via "
                + nameHelper.getName() + "." + nameMethod.getName() + " -> '" + WATERMARK_DEV + "'.");
    }

    // -------------------------------------------------------- watermark name (camera) ---

    /**
     * Camera watermark device name. A family of provider classes (subclasses of C15705)
     * return a {@code SparseArray<String[]>} = [manufacturer, device] from {@code mo26211d()}.
     * Class + method names are non-ASCII obfuscated, so they are located at runtime with
     * DexKit (SparseArray-returning, zero-arg, with the SparseArray&lt;String[]&gt; signature
     * annotation) and forced to {@link #WATERMARK_MFR} / {@link #WATERMARK_DEV}.
     */
    private void hookCameraWatermarkName(final ClassLoader cl, final String apkPath) {
        new Thread(() -> {
            try {
                System.loadLibrary("dexkit");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "could not load libdexkit — camera watermark skipped: " + t);
                return;
            }
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                MethodDataList providers = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType(SparseArray.class)
                                .paramCount(0)
                                .annotations(AnnotationsMatcher.create()
                                        .add(AnnotationMatcher.create()
                                                .usingStrings("Ljava/lang/String;")))));
                int hooked = 0;
                for (MethodData md : providers) {
                    try {
                        Method m = md.getMethodInstance(cl);
                        hook(m).intercept(chain -> {
                            SparseArray<String[]> sa = new SparseArray<>(1);
                            sa.put(0, new String[]{WATERMARK_MFR, WATERMARK_DEV});
                            return sa;
                        });
                        hooked++;
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "failed to hook watermark provider " + md + ": " + t);
                    }
                }
                log(Log.INFO, TAG, "camera watermark providers hooked = " + hooked
                        + " ('" + WATERMARK_MFR + "' / '" + WATERMARK_DEV + "').");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "DexKit camera watermark search failed: " + t);
            }
        }, "LeicaFP-DexKit").start();
    }

    // ------------------------------------------------------------- frames (gallery) -----

    /**
     * The gallery requirement check (2.10.43.1.4: {@code x60.c1.a(Watermark, String,
     * ExifReader, WatermarkGroup)}) returns an error verdict ({@code f70.a$b}) when a
     * required photo field is absent, which surfaces as “未识别到拍摄参数，请更换图片后重试”.
     * Keep the original evaluation, then turn that verdict into its existing success
     * singleton ({@code f70.a$a}) for photos the Xiaomi 17 Ultra camera produced, so the
     * Leica frame renders despite empty fields.
     *
     * <p>Located by shape, not by name: four parameters, non-primitive return type whose
     * nested classes are the result variants, and an EXIF reader as the third parameter.
     * The success/error variants are resolved from the returned type itself, so a
     * re-obfuscated gallery build only needs the outer class name updated.
     */
    private void hookGalleryWatermarkRequirements(ClassLoader cl) {
        Method requirements = null;
        Class<?> resultType = null;
        for (String candidate : WM_VERIFY_CLASSES) {
            Class<?> cls = findClass(candidate, cl);
            if (cls == null) continue;
            for (Method m : cls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 4) continue;
                Class<?> r = m.getReturnType();
                if (r.isPrimitive() || r.isArray()) continue;
                if (r.getDeclaredClasses().length == 0) continue;
                if (findStringGetter(p[2]) == null) continue;
                requirements = m;
                resultType = r;
                break;
            }
            if (requirements != null) break;
        }
        if (requirements == null) {
            log(Log.WARN, TAG, "gallery requirement method not found in "
                    + java.util.Arrays.toString(WM_VERIFY_CLASSES) + " — skipped.");
            return;
        }

        Method exifGetter = findStringGetter(requirements.getParameterTypes()[2]);
        if (exifGetter == null) {
            log(Log.WARN, TAG, "gallery EXIF getter not found — skipped.");
            return;
        }

        Class<?>[] variants = resultType.getDeclaredClasses();
        Object successResult = null;
        for (Class<?> variant : variants) {
            Object value = firstStaticAssignable(variant, resultType);
            if (value != null) {
                successResult = value;
                break;
            }
        }
        Class<?> errorType = null;
        for (Class<?> variant : variants) {
            if (successResult == null || !variant.isInstance(successResult)) {
                errorType = variant;
                break;
            }
        }
        if (successResult == null || errorType == null) {
            log(Log.WARN, TAG, "gallery requirement result variants not found on "
                    + resultType.getName() + " — skipped.");
            return;
        }

        final Object success = successResult;
        final Class<?> error = errorType;
        requirements.setAccessible(true);
        hook(requirements).intercept(chain -> {
            Object original = chain.proceed();
            if (error.isInstance(original)
                    && isTargetCameraPhoto(chain.getArg(2), exifGetter)) {
                return success;
            }
            return original;
        });
        log(Log.INFO, TAG, "gallery requirement hook active on " + requirements
                + " (result=" + resultType.getName()
                + ", success=" + success.getClass().getName() + ").");
    }

    /**
     * Only bypass the gallery/EXIF classification for photos carrying original
     * Xiaomi 17 Ultra camera metadata. Screenshots and imported images without a
     * camera capture timestamp keep the stock unsupported-photo error.
     */
    private boolean isTargetCameraPhoto(Object photo, Method getter) {
        String make = exif(photo, getter, "Make");
        String model = exif(photo, getter, "Model");
        String date = exif(photo, getter, "DateTimeOriginal");
        String product = exif(photo, getter, "XiaomiProduct");

        boolean xiaomi = containsIgnoreCase(make, "Xiaomi") || nonEmpty(product);
        boolean targetDevice = containsIgnoreCase(model, "17 Ultra")
                || containsIgnoreCase(model, "nezha")
                || containsIgnoreCase(product, "17 Ultra")
                || containsIgnoreCase(product, "nezha");
        return xiaomi && targetDevice && nonEmpty(date);
    }

    private String exif(Object photo, Method getter, String key) {
        if (photo == null) return null;
        try {
            Object value = getter.invoke(photo, key);
            return value instanceof String ? ((String) value).trim() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null
                && value.toLowerCase(java.util.Locale.ROOT)
                .contains(expected.toLowerCase(java.util.Locale.ROOT));
    }

    private boolean nonEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Watermark config transformation asks Xiaomi for network time with a
     * 2000 ms timeout. Substitute the device clock only for that exact call;
     * other app features use 1500/5000 ms and remain unchanged.
     */
    private void hookWatermarkNetworkTime(ClassLoader cl) {
        Class<?> networkTime = null;
        Method target = null;
        for (String candidate : NETWORK_TIME_CLASSES) {
            Class<?> cls = findClass(candidate, cl);
            if (cls == null) continue;
            for (Method m : cls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != long.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == long.class && "a".equals(m.getName())) {
                    target = m;
                    networkTime = cls;
                    break;
                }
            }
            if (target != null) break;
        }
        if (target == null) {
            log(Log.WARN, TAG, "network-time method a(long) not found in "
                    + java.util.Arrays.toString(NETWORK_TIME_CLASSES) + " — skipped.");
            return;
        }

        target.setAccessible(true);
        hook(target).intercept(chain -> {
            Object arg = chain.getArg(0);
            if (arg instanceof Number && ((Number) arg).longValue() == 2000L) {
                return System.currentTimeMillis();
            }
            return chain.proceed();
        });
        log(Log.INFO, TAG, "watermark network-time hook active on "
                + networkTime.getName() + ".a(long) (2000 ms only).");
    }

    /** Find the success singleton without depending on its field name. */
    private Object firstStaticAssignable(Class<?> cls, Class<?> type) {
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!type.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object value = f.get(null);
                if (value != null) return value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Method findStaticStringMethod(Class<?> cls, String name, int paramCount) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != String.class) continue;
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length != paramCount) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /**
     * Static {@code String} method of the given arity whose first parameter looks like the
     * gallery EXIF reader — name-independent fallback for re-obfuscated mediaeditor builds.
     */
    private Method findStaticStringWithExif(Class<?> cls, int paramCount) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getParameterTypes().length != paramCount) continue;
            if (findStringGetter(m.getParameterTypes()[0]) == null) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /**
     * The mediaeditor EXIF reader exposes {@code String get(String key)}; the obfuscated
     * name changes between builds (2.10.43.x uses {@code f}), so match the shape instead.
     */
    private Method findStringGetter(Class<?> cls) {
        if (cls == null) return null;
        Method fallback = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterTypes().length != 1) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            if (m.getReturnType() != String.class) continue;
            m.setAccessible(true);
            // 2.10.43.x exposes the EXIF reader as f(String); keep it first and only fall
            // back to any other String->String method if a future build renames it.
            if ("f".equals(m.getName())) return m;
            if (fallback == null) fallback = m;
        }
        return fallback;
    }

    // ----------------------------------------------------------------- reflection -------

    private Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Method by name and parameter count (searches the class only). */
    private Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

}
