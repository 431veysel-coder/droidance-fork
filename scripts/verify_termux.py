#!/usr/bin/env python3
"""
Verify Termux bootstrap integrity and critical files.
Run in CI to ensure bootstrap is valid before building.
"""
import hashlib
import json
import sys
import urllib.request
from pathlib import Path
import zipfile

BOOTSTRAP_URL = "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"
EXPECTED_MIN_SIZE = 30_000_000  # 30 MB
CRITICAL_FILES = [
    "bin/bash",
    "bin/apt",
    "bin/pkg",
    "lib/libc.so",
    "lib/libc++_shared.so",
    "lib/libandroid-support.so",
    "SYMLINKS.txt"
]

def download_bootstrap(dest: Path) -> None:
    print(f"Downloading bootstrap from {BOOTSTRAP_URL}...")
    urllib.request.urlretrieve(BOOTSTRAP_URL, dest)
    size = dest.stat().st_size
    print(f"Downloaded {size} bytes")
    if size < EXPECTED_MIN_SIZE:
        raise ValueError(f"Bootstrap too small: {size} < {EXPECTED_MIN_SIZE}")

def verify_sha256(file: Path) -> str:
    h = hashlib.sha256()
    with file.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    sha256 = h.hexdigest()
    print(f"SHA256: {sha256}")
    return sha256

def verify_critical_files(zip_path: Path) -> None:
    print("Verifying critical files in bootstrap...")
    with zipfile.ZipFile(zip_path, 'r') as zf:
        missing = []
        for rel in CRITICAL_FILES:
            try:
                info = zf.getinfo(rel)
                if info.file_size == 0:
                    missing.append(f"{rel} (empty)")
            except KeyError:
                missing.append(f"{rel} (missing)")

        if missing:
            raise ValueError(f"Critical files missing/empty: {missing}")

        # Verify symlinks can be parsed
        try:
            symlinks_content = zf.read("SYMLINKS.txt").decode("utf-8")
            lines = [l.strip() for l in symlinks_content.splitlines() if l.strip() and not l.startswith("#")]
            print(f"SYMLINKS.txt: {len(lines)} entries")
            # Sample check
            for line in lines[:5]:
                if "←" in line:
                    target, link = line.split("←", 1)
                    print(f"  {link.strip()} -> {target.strip()}")
        except Exception as e:
            raise ValueError(f"SYMLINKS.txt parse failed: {e}")

        print("✅ All critical files present")

def main():
    import tempfile
    with tempfile.TemporaryDirectory() as tmpdir:
        zip_path = Path(tmpdir) / "bootstrap-aarch64.zip"
        try:
            download_bootstrap(zip_path)
            sha256 = verify_sha256(zip_path)
            verify_critical_files(zip_path)

            # Output for CI
            result = {
                "sha256": sha256,
                "size": zip_path.stat().st_size,
                "critical_files": CRITICAL_FILES,
                "status": "ok"
            }
            print(json.dumps(result, indent=2))
            return 0
        except Exception as e:
            print(f"❌ Verification failed: {e}", file=sys.stderr)
            return 1

if __name__ == "__main__":
    sys.exit(main())