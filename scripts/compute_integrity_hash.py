#!/usr/bin/env python3
"""
Portal libportal.so Integrity Hash Calculator

Replicates the C++ FNV-1a + avalanche mixing chain from integrity.cpp.
Use this to compute the expected kExpectedHash when changing:
  - APPLICATION_ID / VERSION_NAME / VERSION_CODE in PortalIdentity.kt
  - LEACY_PACKAGE_ID (pkgName2 in the hash chain)

Usage:
    python scripts/compute_integrity_hash.py \\
        --pkg tv.kyshu \\
        --pkg2 tv.kyshu \\
        --version 1.0.4 \\
        --version-code 10004

    # Read values directly from PortalIdentity.kt:
    python scripts/compute_integrity_hash.py --auto
"""

import argparse
import re
import sys
from pathlib import Path

MASK64 = 0xFFFFFFFFFFFFFFFF
FNV_PRIME = 0x100000001B3
FNV_SEED = 0x811C9DC5


def fnv1a(s: str, seed: int) -> int:
    h = seed
    for c in s.encode("utf-8"):
        h ^= c
        h = (h * FNV_PRIME) & MASK64
    return h


def hash_mix(val: int, salt: int) -> int:
    x = (val * salt) & MASK64
    x ^= (x >> 33)
    x = (x * 0xFF51AFD7ED558CCD) & MASK64
    x ^= (x >> 33)

    x = ((x << 17) | (x >> 47)) & MASK64
    x = (x * 0xC4CEB9FE1A85EC53) & MASK64
    x ^= (x >> 33)

    x = (x + salt) & MASK64
    x = (x * 0x94D049BB133111EB) & MASK64
    x ^= (x >> 31)

    x = (x * 0xBF58476D1CE4E5B9) & MASK64
    x ^= (x >> 31)

    x = (x * 0x6D4A8F2E1B9C3D5E) & MASK64
    return x


def compute_integrity(pkg: str, pkg2: str, version: str, version_code: int) -> int:
    h1 = fnv1a(pkg, FNV_SEED)
    h2 = fnv1a(pkg2, h1)
    h3 = fnv1a(version, h2)
    h4 = hash_mix(h3, version_code)
    return h4


def auto_read_values() -> dict:
    """Parse PortalIdentity.kt for current values."""
    identity_path = (
        Path(__file__).resolve().parent.parent
        / "xposed/src/main/java/tv/kyshu/xposed/utils/PortalIdentity.kt"
    )
    if not identity_path.exists():
        print(f"Error: {identity_path} not found", file=sys.stderr)
        sys.exit(1)

    text = identity_path.read_text(encoding="utf-8")
    values = {}

    m = re.search(r'const val APPLICATION_ID\s*=\s*"([^"]+)"', text)
    if m:
        values["pkg"] = m.group(1)

    m = re.search(r'const val LEGACY_PACKAGE_ID\s*=\s*"([^"]+)"', text)
    if m:
        values["pkg2"] = m.group(1)

    m = re.search(r'const val VERSION_NAME\s*=\s*"([^"]+)"', text)
    if m:
        values["version"] = m.group(1)

    m = re.search(r"const val VERSION_CODE\s*=\s*(\d+)", text)
    if m:
        values["version_code"] = int(m.group(1))

    return values


def update_cpp_header(expected_hash: int) -> None:
    """Update integrity.cpp with the new expected hash."""
    cpp_path = (
        Path(__file__).resolve().parent.parent
        / "xposed/src/main/cpp/integrity.cpp"
    )
    if not cpp_path.exists():
        print(f"Warning: {cpp_path} not found, skipping update", file=sys.stderr)
        return

    text = cpp_path.read_text(encoding="utf-8")
    new_line = f"static const uint64_t kExpectedHash = 0x{expected_hash:016X}ULL;"
    text = re.sub(
        r"static const uint64_t kExpectedHash = 0x[0-9A-Fa-f]+ULL;",
        new_line,
        text,
    )
    cpp_path.write_text(encoding="utf-8", data=text)
    print(f"Updated integrity.cpp: {new_line}")


def main():
    parser = argparse.ArgumentParser(
        description="Compute Portal integrity hash for libportal.so"
    )
    parser.add_argument("--pkg", help="APPLICATION_ID (pkgName)")
    parser.add_argument("--pkg2", help="LEGACY_PACKAGE_ID or control package (pkgName2)")
    parser.add_argument("--version", help="VERSION_NAME")
    parser.add_argument("--version-code", type=int, help="VERSION_CODE")
    parser.add_argument(
        "--auto",
        action="store_true",
        help="Read values from PortalIdentity.kt automatically",
    )
    parser.add_argument(
        "--update",
        action="store_true",
        help="Also update kExpectedHash in integrity.cpp",
    )

    args = parser.parse_args()

    if args.auto:
        values = auto_read_values()
    elif args.pkg and args.pkg2 and args.version and args.version_code is not None:
        values = {
            "pkg": args.pkg,
            "pkg2": args.pkg2,
            "version": args.version,
            "version_code": args.version_code,
        }
    else:
        parser.print_help()
        print("\nError: provide all four values or --auto", file=sys.stderr)
        sys.exit(1)

    print("Input values:")
    for k, v in values.items():
        print(f"  {k}: {v}")

    h = compute_integrity(
        values["pkg"], values["pkg2"], values["version"], values["version_code"]
    )

    print(f"\nComputed hash: 0x{h:016X}")
    print(f"C++ constant:  kExpectedHash = 0x{h:016X}ULL;")

    if args.update:
        update_cpp_header(h)

    # Return the hash as exit code (bit 0-7) for script chaining
    return h & 0xFF


if __name__ == "__main__":
    rc = main()
    sys.exit(rc)
