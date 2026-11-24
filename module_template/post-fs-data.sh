#!/system/bin/sh
# Portal (tv.kyshu) KernelSU module — post-fs-data stage.
# Ported from Kuroko-Teleport boot-guard anti-brick design (post-fs-data.sh).
#
# Boot-guard protocol:
#   - Increment /data/adb/kyshu/boot_guard on EVERY boot attempt.
#   - service.sh resets it to 0 only after sys.boot_completed=1 (+3 s grace).
#   - If the counter reaches 2+ (two consecutive unfinished boots), every hook
#     layer checks this file and refuses to engage — the device boots clean.
#
# Portal is APK-only (no native daemon): this script only maintains the guard
# state dir and hands the guard value to the Zygisk companion via the state dir.

MODDIR=${0%/*}
STATE_DIR="/data/adb/kyshu"
GUARD_FILE="$STATE_DIR/boot_guard"

# Ensure state directory exists with root-only permissions
mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"
chown 0:0 "$STATE_DIR" 2>/dev/null

# Read and increment anti-bootloop boot_guard counter
COUNT=0
if [ -f "$GUARD_FILE" ]; then
    RAW=$(cat "$GUARD_FILE" 2>/dev/null | tr -dc '0-9')
    if [ -n "$RAW" ]; then
        COUNT=$RAW
    fi
fi
COUNT=$((COUNT + 1))
printf '%s\n' "$COUNT" > "$GUARD_FILE"
chmod 0600 "$GUARD_FILE"

# Nothing else to start at post-fs-data: Portal has no root daemon.
# The Zygisk companion (if installed) reads the guard file itself.
exit 0
