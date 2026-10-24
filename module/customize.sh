#!/system/bin/sh
#
# Magisk / KSU customize.sh
# Installs the bundled LSPOSED companion APK (Leica Frame Patcher) during flash.
# The system props are applied by Magisk/KSU from system.prop automatically.
#
# NOTE: flashing runs inside Android (Magisk app / KSU-Next), where `pm` exists,
# so the APK installs at flash time. Recovery flashing has no `pm` and will skip
# the APK install (props still apply).

APK="$MODPATH/LeicaFramePatcher.apk"

ui_print "- Nezha Leica Camera Enabler"
ui_print "- Applying Leica system properties"

if [ -f "$APK" ]; then
  installed=0
  reset_scope=0
  if command -v pm >/dev/null 2>&1; then
    ui_print "- Installing LSPOSED module APK (Leica Frame Patcher)"
    if pm install -r -g "$APK" >/dev/null 2>&1; then
      installed=1
    else
      ui_print "! Update was rejected (usually an APK signing-key change)."
      ui_print "- Removing the old companion APK and installing the new one."
      pm uninstall io.github.mexusbg.leicaframes >/dev/null 2>&1
      if pm install -g "$APK" >/dev/null 2>&1; then
        installed=1
        reset_scope=1
      fi
    fi
  fi

  if [ "$installed" = "1" ]; then
    ui_print "  APK installed."
    if [ "$reset_scope" = "1" ]; then
      ui_print "  ! The signing key changed, so LSPOSED scope was reset."
    fi
    ui_print "  >> Open LSPOSED, ENABLE 'Leica Frame Patcher',"
    ui_print "  >> set scope: Camera + Gallery editor + Settings, then reboot."
  else
    ui_print "! Auto-install failed (recovery flash, pm unavailable, or install error)."
    ui_print "! Install it manually after boot:"
    ui_print "!   /data/adb/modules/$MODID/LeicaFramePatcher.apk"
    ui_print "! Then enable it in LSPOSED (scope: Camera + Gallery editor + Settings)."
  fi
else
  ui_print "! Bundled APK not found — props-only install."
fi
