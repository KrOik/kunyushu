#!/system/bin/sh
# Portal (tv.kyshu) KernelSU module — installer (customize.sh).
# Ported from Kuroko-Teleport customize.sh: streaming APK install with SHA-256
# verification, deferred-install fallback, data-preserving semantics.

SKIPUNZIP=0

STATE_DIR="/data/adb/kyshu"
PENDING_DIR="$STATE_DIR/pending_apk"

ui_print() { echo "$1"; }

abort() {
    ui_print "ERROR: $1"
    exit 1
}

# ---------- locate the module directory (ksud sets MODDIR; be defensive) ----------
if [ -z "$MODDIR" ] || [ ! -d "$MODDIR" ]; then
    SCRIPT_PATH="$0"
    case "$SCRIPT_PATH" in
        */*) MODDIR="${SCRIPT_PATH%/*}" ;;
        *)   MODDIR="$(pwd)" ;;
    esac
fi

# ---------- extract embedded APK (search fallbacks: ksud may run us early) ----------
APK_PATH=""
for CAND in "$MODDIR/portal.apk" "$(pwd)/portal.apk" "/data/adb/modules_update/portal_ksu/portal.apk"; do
    if [ -f "$CAND" ]; then
        APK_PATH="$CAND"
        break
    fi
done
if [ -z "$APK_PATH" ]; then
    # Last resort: unzip from the original zip passed by the installer (env KSU_ZIP)
    if [ -n "$ZIPFILE" ] && [ -f "$ZIPFILE" ]; then
        mkdir -p "$MODDIR"
        unzip -o "$ZIPFILE" portal.apk -d "$MODDIR" >/dev/null 2>&1
        [ -f "$MODDIR/portal.apk" ] && APK_PATH="$MODDIR/portal.apk"
    fi
fi
if [ -z "$APK_PATH" ]; then
    abort "portal.apk missing from module zip"
fi

APK_SIZE=$(wc -c < "$APK_PATH" | tr -dc '0-9')
APK_SHA256=$(sha256sum "$APK_PATH" 2>/dev/null | cut -d' ' -f1 | tr -d ' ')
if [ -z "$APK_SIZE" ] || [ "$APK_SIZE" -lt 1024 ]; then
    abort "embedded APK size invalid ($APK_SIZE)"
fi

PKG_NAME="tv.kyshu"
# Best-effort packageName extraction from the APK manifest via aapt (not always present)
if command -v aapt >/dev/null 2>&1; then
    DETECTED=$(aapt dump badging "$APK_PATH" 2>/dev/null | grep -m1 "^package:" | cut -d"'" -f2)
    [ -n "$DETECTED" ] && PKG_NAME="$DETECTED"
fi

ui_print "- Portal module: embedded APK $APK_SIZE bytes, sha256=${APK_SHA256:0:16}..."

# ---------- try immediate install (PMS may be up in KernelSU flash context) ----------
INSTALLED=0
PMS_READY=0
for i in $(seq 1 5); do
    if pm path android >/dev/null 2>&1 || cmd package path android >/dev/null 2>&1; then
        PMS_READY=1
        break
    fi
    sleep 1
done

if [ "$PMS_READY" -eq 1 ]; then
    PM_OUT=$(pm install -r -d -S "$APK_SIZE" < "$APK_PATH" 2>&1)
    if printf '%s' "$PM_OUT" | grep -q "Success"; then
        INSTALLED=1
        ui_print "- APK installed via pm (streaming)"
    elif printf '%s' "$PM_OUT" | grep -Eq "UPDATE_INCOMPATIBLE|SIGNATURES"; then
        # Policy A2: never uninstall an existing app. The currently installed
        # Portal stays; user data preserved. Module continues with deferred skip.
        ui_print "! Signature conflict: existing app preserved, skipping APK install"
        INSTALLED=2
    fi
fi

if [ "$INSTALLED" -eq 0 ]; then
    # Stage for service.sh deferred install (Recovery flash context)
    mkdir -p "$PENDING_DIR"
    chmod 0700 "$PENDING_DIR"
    cp -f "$APK_PATH" "$PENDING_DIR/portal.apk"
    printf 'packageName=%s\nversionName=%s\nversionCode=%s\nsize=%s\nsha256=%s\n' \
        "$PKG_NAME" "$ui_pkgver" "$ui_pkgvercode" "$APK_SIZE" "$APK_SHA256" \
        > "$PENDING_DIR/apk_meta.prop"
    chmod 0600 "$PENDING_DIR/apk_meta.prop"
    ui_print "- PMS not ready: APK staged for deferred install on next boot"
fi

# ---------- state dir ----------
mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

# ---------- LSPosed activation note ----------
ui_print "- LSPosed: if not auto-enabled, activate 'Portal' in LSPosed manager"
ui_print "- Scope MUST stay: android, com.android.phone, fused providers only."
ui_print "- NEVER check target third-party apps in scope (zero-injection design)."

ui_print "- Portal module install complete."
exit 0
