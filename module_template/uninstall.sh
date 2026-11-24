#!/system/bin/sh
# Portal (tv.kyshu) KernelSU module — uninstall script.
# Policy A2 (Kuroko port): NEVER run `pm uninstall` — the Portal app and all its
# user data (routes, environment captures, settings) survive module removal.
# LSPosed scope survives too; the user can disable/uninstall the app at will.

# Clean runtime state only
rm -rf /data/adb/kyshu/pending_apk 2>/dev/null
rm -f /data/adb/kyshu/service.log 2>/dev/null
rm -f /data/adb/kyshu/apk_installed.prop 2>/dev/null

# Keep boot_guard counter file? Remove it — fresh installs start fresh.
rm -f /data/adb/kyshu/boot_guard 2>/dev/null

exit 0
