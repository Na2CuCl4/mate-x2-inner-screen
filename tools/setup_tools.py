"""Fetch pinned official Windows tools into a local, project-external directory."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = PROJECT.parent / "tmp" / "innerlock-build-tools"
PACKAGES = {
    "jdk": {
        "url": "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip",
        "hash_type": "sha256",
        "hash": "e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0",
        "size": 190817615,
        "marker": "bin/javac.exe",
        "provenance": "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip.sha256.txt",
    },
    "build-tools": {
        "url": "https://dl.google.com/android/repository/build-tools_r35_windows.zip",
        "hash_type": "sha1",
        "hash": "af059bb67cf7786f45ee0db85e2d24985df1b4b6",
        "size": 59878107,
        "marker": "aapt2.exe",
        "provenance": "https://dl.google.com/android/repository/repository2-3.xml (build-tools;35.0.0, windows)",
    },
    "platform": {
        "url": "https://dl.google.com/android/repository/platform-35_r02.zip",
        "hash_type": "sha1",
        "hash": "0bb560a90a7a2cbd0dd8348224d518b638fe7949",
        "size": 64273788,
        "marker": "android.jar",
        "provenance": "https://dl.google.com/android/repository/repository2-3.xml (platforms;android-35, revision 2)",
    },
}


def valid_archive(path: Path, package: dict) -> bool:
    if not path.is_file() or path.stat().st_size != package["size"]:
        return False
    digest = hashlib.new(package["hash_type"])
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == package["hash"]


def install_one(root: Path, name: str, package: dict) -> tuple[str, str]:
    destination = root / name
    marker = destination / package["marker"]
    if marker.is_file():
        print(f"Using existing {name}: {destination}", flush=True)
        return name, str(destination)
    archive = root / "downloads" / Path(package["url"]).name
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not valid_archive(archive, package):
        partial = archive.with_suffix(archive.suffix + ".part")
        print(f"Downloading {name} ({package['size'] / 1024 / 1024:.1f} MiB)", flush=True)
        request = urllib.request.Request(package["url"], headers={"User-Agent": "InnerScreen-local-build/1.0"})
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        if not valid_archive(partial, package):
            raise RuntimeError(f"Official archive checksum/size did not match for {name}")
        partial.replace(archive)
    print(f"Verified {name} {package['hash_type']}: {package['hash']}", flush=True)
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        roots = {entry.filename.split("/", 1)[0] for entry in entries if "/" in entry.filename}
        if len(roots) != 1:
            raise RuntimeError(f"Unexpected archive layout for {name}: {roots}")
        prefix = next(iter(roots)) + "/"
        for entry in entries:
            if not entry.filename.startswith(prefix):
                continue
            relative = entry.filename[len(prefix):]
            if not relative:
                continue
            target = (destination / relative).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise RuntimeError(f"Archive path escapes extraction folder: {entry.filename}")
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(entry) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
    if not marker.is_file():
        raise RuntimeError(f"Missing expected tool after extracting {name}: {marker}")
    print(f"Ready {name}: {destination}", flush=True)
    return name, str(destination)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools-root", type=Path, default=DEFAULT_ROOT)
    args = parser.parse_args()
    root = args.tools_root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(install_one, root, name, package) for name, package in PACKAGES.items()]
        locations = dict(future.result() for future in futures)
    (root / "toolchain.json").write_text(json.dumps({"locations": locations, "packages": PACKAGES}, indent=2), encoding="utf-8")
    print("Toolchain ready. Nothing was installed globally.", flush=True)


if __name__ == "__main__":
    main()
