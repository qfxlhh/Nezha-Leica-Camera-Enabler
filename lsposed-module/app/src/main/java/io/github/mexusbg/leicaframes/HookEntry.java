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

    // mediaeditor 2.10.43.0.6 names.
    private static final String CLS_WM_NAME = "o80.h";
    private static final String CLS_WM_VERIFY = "w60.x0";
    private static final String CLS_WM_VERIFY_RESULT = "e70.a";
    private static final String CLS_WM_VERIFY_ERROR = "e70.a$b";
    private static final String CLS_WM_VERIFY_SUCCESS = "e70.a$a";
    private static final String CLS_NETWORK_TIME = "aa0.a";

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

    /** Gallery watermark device name for mediaeditor 2.10.43.0.6. */
    private void hookGalleryWatermarkName(ClassLoader cl) {
        Class<?> nameHelper = findClass(CLS_WM_NAME, cl);
        if (nameHelper == null) {
            log(Log.WARN, TAG, CLS_WM_NAME + " not found — gallery name hook skipped.");
            return;
        }

        Method nameMethod = findStaticStringMethod(nameHelper, "o", 3);
        if (nameMethod == null) {
            log(Log.WARN, TAG, "gallery watermark-name method o(...) not found.");
            return;
        }

        Method exifGetter = findMethod(nameMethod.getParameterTypes()[0], "f", 1);
        if (exifGetter == null) {
            log(Log.WARN, TAG, "gallery watermark-name EXIF getter not found — skipped.");
            return;
        }

        hook(nameMethod).intercept(chain ->
                isTargetCameraPhoto(chain.getArg(0), exifGetter)
                        ? WATERMARK_DEV
                        : chain.proceed());
        log(Log.INFO, TAG, "gallery watermark name override active -> '" + WATERMARK_DEV + "'.");
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
     * w60.x0.a(...) is called directly by MME_PhotoWatermarkTemplateUtils as well
     * as by the higher-level agent validation. It returns e70.a$b when any required photo
     * field is absent. Keep the original evaluation, then turn that verdict into
     * its existing success singleton so the template may render with blank fields.
     */
    private void hookGalleryWatermarkRequirements(ClassLoader cl) {
        Class<?> verify = findClass(CLS_WM_VERIFY, cl);
        Class<?> result = findClass(CLS_WM_VERIFY_RESULT, cl);
        Class<?> error = findClass(CLS_WM_VERIFY_ERROR, cl);
        Class<?> success = findClass(CLS_WM_VERIFY_SUCCESS, cl);
        if (verify == null || result == null || error == null || success == null) {
            log(Log.WARN, TAG, "gallery requirement lookup failed (verify=" + verify
                    + " result=" + result + " error=" + error + " success=" + success
                    + ") — skipped.");
            return;
        }

        Method requirements = null;
        for (Method m : verify.getDeclaredMethods()) {
            if (m.getReturnType() == result && m.getParameterTypes().length == 4) {
                requirements = m;
                break;
            }
        }
        if (requirements == null) {
            log(Log.WARN, TAG, "gallery requirement method not found on "
                    + CLS_WM_VERIFY + " — skipped.");
            return;
        }

        Method exifGetter = findMethod(requirements.getParameterTypes()[2], "f", 1);
        if (exifGetter == null) {
            log(Log.WARN, TAG, "gallery EXIF getter not found — skipped.");
            return;
        }

        final Object successResult = firstStaticAssignable(success, result);
        if (successResult == null) {
            log(Log.WARN, TAG, "gallery requirement success singleton not found on "
                    + CLS_WM_VERIFY_SUCCESS + " — skipped.");
            return;
        }

        final Class<?> errorType = error;
        requirements.setAccessible(true);
        hook(requirements).intercept(chain -> {
            Object original = chain.proceed();
            if (errorType.isInstance(original)
                    && isTargetCameraPhoto(chain.getArg(2), exifGetter)) {
                return successResult;
            }
            return original;
        });
        log(Log.INFO, TAG, "gallery requirement hook active on " + requirements + ".");
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
        Class<?> networkTime = findClass(CLS_NETWORK_TIME, cl);
        if (networkTime == null) {
            log(Log.WARN, TAG, CLS_NETWORK_TIME + " not found — network-time hook skipped.");
            return;
        }

        Method target = null;
        for (Method m : networkTime.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != long.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == long.class && "a".equals(m.getName())) {
                target = m;
                break;
            }
        }
        if (target == null) {
            log(Log.WARN, TAG, "network-time method a(long) not found — skipped.");
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
        log(Log.INFO, TAG, "watermark network-time hook active (2000 ms only).");
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
