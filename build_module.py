#!/usr/bin/env python3
"""Package the Portal (tv.kyshu) All-in-One KernelSU module ZIP.

Ported from Kuroko-Teleport build_module.py (simplified: no native ELF audit,
no daemon bin/). Embeds the built Portal APK into module_template/ with
ZIP_STORED + SHA-256 metadata, then packs the module zip.

Usage:
    python3 build_module.py [--apk path/to/app.apk] [--out dist/portal-ksu-module.zip]
"""

import argparse
import hashlib
import os
import shutil
import stat
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_DIR = os.path.join(ROOT, "module_template")
STAGING_DIR = os.path.join(ROOT, "build", "module_staging")
DIST_DIR = os.path.join(ROOT, "dist")
ZIP_PATH = os.path.join(DIST_DIR, "portal-ksu-module.zip")
DEFAULT_APK_PATH = os.path.join(
    ROOT, "app", "build", "outputs", "apk", "app", "app-app-debug.apk"
)

TEMPLATE_FILES = {
    "module.prop": 0o644,
    "customize.sh": 0o755,
    "post-fs-data.sh": 0o755,
    "service.sh": 0o755,
    "uninstall.sh": 0o755,
}

def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def read_module_prop(staging: str) -> dict:
    props = {}
    with open(os.path.join(staging, "module.prop"), "r", encoding="utf-8") as f:
        for line in f:
            if "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props

def verify_zip_archive(zf: zipfile.ZipFile, staging: str) -> None:
    """Integrity gate: every staging file must be present, STORED, and byte-identical."""
    expected = {}
    for root, _dirs, files in os.walk(staging):
        for name in files:
            p = os.path.join(root, name)
            rel = os.path.relpath(p, staging).replace(os.sep, "/")
            expected[rel] = p
    names = set(zf.namelist())
    if names != set(expected.keys()):
        missing = set(expected.keys()) - names
        extra = names - set(expected.keys())
        raise SystemExit(f"zip content mismatch: missing={missing} extra={extra}")
    for rel, path in expected.items():
        info = zf.getinfo(rel)
        if info.compress_type != zipfile.ZIP_STORED:
            raise SystemExit(f"{rel}: expected ZIP_STORED for KernelSU module")
        with open(path, "rb") as f:
            data = f.read()
        if len(data) != info.file_size:
            raise SystemExit(f"{rel}: size mismatch")
        if hashlib.sha256(data).hexdigest() != sha256_file(path):
            raise SystemExit(f"{rel}: content mismatch")

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=DEFAULT_APK_PATH, help="Portal APK to embed")
    ap.add_argument("--out", default=ZIP_PATH, help="output zip path")
    args = ap.parse_args()

    apk = os.path.abspath(args.apk)
    if not os.path.isfile(apk):
        raise SystemExit(f"APK not found: {apk}")

    apk_sha = sha256_file(apk)
    apk_size = os.path.getsize(apk)

    # staging
    if os.path.exists(STAGING_DIR):
        shutil.rmtree(STAGING_DIR)
    os.makedirs(STAGING_DIR, exist_ok=True)
    for name, mode in TEMPLATE_FILES.items():
        src = os.path.join(TEMPLATE_DIR, name)
        if not os.path.isfile(src):
            raise SystemExit(f"template file missing: {src}")
        dst = os.path.join(STAGING_DIR, name)
        shutil.copyfile(src, dst)
        os.chmod(dst, mode)

    # embed APK as portal.apk (ZIP_STORED so KernelSU can stream it)
    apk_staged = os.path.join(STAGING_DIR, "portal.apk")
    shutil.copyfile(apk, apk_staged)
    os.chmod(apk_staged, 0o644)

    # patch customize.sh-apk_meta fallback: write apk_meta.prop next to module.prop
    meta = os.path.join(STAGING_DIR, "apk_meta.prop")
    with open(meta, "w", encoding="utf-8") as f:
        f.write(f"packageName=tv.kyshu\nsize={apk_size}\nsha256={apk_sha}\n")
    os.chmod(meta, 0o644)

    # pack
    os.makedirs(DIST_DIR, exist_ok=True)
    out = os.path.abspath(args.out)
    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(out, "w") as zf:
        for root, _dirs, files in os.walk(STAGING_DIR):
            for name in sorted(files):
                p = os.path.join(root, name)
                rel = os.path.relpath(p, STAGING_DIR).replace(os.sep, "/")
                zf.write(p, rel, compress_type=zipfile.ZIP_STORED)

    # verify
    with zipfile.ZipFile(out, "r") as zf:
        verify_zip_archive(zf, STAGING_DIR)

    print(f"OK: {out} ({os.path.getsize(out)} bytes)")
    print(f"    embedded APK: {apk} sha256={apk_sha}")

if __name__ == "__main__":
    main()
