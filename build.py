"""Build and locally sign the standalone Java Android app without Gradle."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import zipfile

PROJECT = Path(__file__).resolve().parent
DEFAULT_TOOLS = PROJECT.parent / "tmp" / "innerlock-build-tools"
PACKAGE = "top.xiazr20.innerlock"


def run(arguments: list[str | Path], label: str) -> None:
    print(f"[{label}]", flush=True)
    # Some Windows aapt2 file APIs cannot stat a non-ASCII absolute filename.
    # The executable is launched through Unicode Win32 APIs; file parameters
    # use project-relative paths, keeping this project/toolchain layout ASCII.
    command = [str(arguments[0])]
    command.extend(os.path.relpath(arg, PROJECT) if isinstance(arg, Path) else str(arg) for arg in arguments[1:])
    subprocess.run(command, cwd=PROJECT, check=True)


def zip_add(bundle: zipfile.ZipFile, source: Path, name: str) -> None:
    entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    bundle.writestr(entry, source.read_bytes())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools-root", type=Path, default=DEFAULT_TOOLS)
    args = parser.parse_args()
    toolroot = args.tools_root.resolve()
    java = toolroot / "jdk" / "bin" / "java.exe"
    javac = toolroot / "jdk" / "bin" / "javac.exe"
    keytool = toolroot / "jdk" / "bin" / "keytool.exe"
    sdk = toolroot / "build-tools"
    android_jar = toolroot / "platform" / "android.jar"
    for required in (java, javac, keytool, sdk / "aapt2.exe", sdk / "zipalign.exe", sdk / "lib" / "d8.jar", sdk / "lib" / "apksigner.jar", android_jar):
        if not required.is_file():
            raise SystemExit(f"Missing tool: {required}\nRun python tools/setup_tools.py first.")
    manifest = PROJECT / "AndroidManifest.xml"
    sources = sorted((PROJECT / "src").rglob("*.java"))
    if not manifest.is_file() or not sources:
        raise SystemExit("AndroidManifest.xml and src/**/*.java must be present before building.")
    build = (PROJECT / "build").resolve()
    if build.parent != PROJECT or build.name != "build":
        raise SystemExit("Refusing to clean a build directory outside this project.")
    if build.exists():
        shutil.rmtree(build)
    for name in ("generated", "classes", "dex"):
        (build / name).mkdir(parents=True, exist_ok=True)
    dist = PROJECT / "dist"
    dist.mkdir(exist_ok=True)
    resource_zip = build / "compiled-res.zip"
    resources_apk = build / "resources.apk"
    run([sdk / "aapt2.exe", "compile", "--dir", "res", "-o", resource_zip], "Compile resources")
    link = [sdk / "aapt2.exe", "link", "-o", resources_apk, "-I", android_jar, "--manifest", manifest,
            "--java", build / "generated", "--custom-package", PACKAGE, "--min-sdk-version", "26",
            "--target-sdk-version", "31", "--auto-add-overlay"]
    if (PROJECT / "assets").is_dir():
        link.extend(["-A", "assets"])
    link.append(resource_zip)
    run(link, "Link APK resources")
    sources += sorted((build / "generated").rglob("*.java"))
    run([javac, "-encoding", "UTF-8", "--release", "8", "-classpath", android_jar,
         "-d", build / "classes", *sources], "Compile Java")
    classes_jar = build / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w") as bundle:
        for compiled in sorted((build / "classes").rglob("*.class")):
            zip_add(bundle, compiled, compiled.relative_to(build / "classes").as_posix())
    run([java, "-cp", sdk / "lib" / "d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", "26",
         "--lib", android_jar, "--output", build / "dex", classes_jar], "Create Android DEX")
    unsigned = build / "unsigned.apk"
    shutil.copyfile(resources_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a") as bundle:
        dex_files = sorted((build / "dex").glob("classes*.dex"))
        if not dex_files:
            raise SystemExit("D8 produced no DEX files.")
        for dex in dex_files:
            zip_add(bundle, dex, dex.name)
    aligned = build / "aligned.apk"
    run([sdk / "zipalign.exe", "-f", "4", unsigned, aligned], "Align APK")
    private = PROJECT / ".local"
    private.mkdir(exist_ok=True)
    keystore = private / "innerlock-release.p12"
    password_file = private / "signing-password.txt"
    if not keystore.exists():
        if not password_file.exists():
            descriptor = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="ascii") as output:
                output.write(secrets.token_urlsafe(32) + "\n")
        run([keytool, "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12", "-alias", "innerlock",
             "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-storepass:file", password_file,
             "-dname", "CN=Mate X2 Inner Screen, OU=Personal Apps, O=Weng, C=CN", "-noprompt"], "Create local signing key")
    if not password_file.is_file():
        raise SystemExit("Signing key exists but its local password file is missing.")
    apk = dist / "Mate-X2-InnerScreen.apk"
    signer = [java, "-jar", sdk / "lib" / "apksigner.jar"]
    run([*signer, "sign", "--ks", keystore, "--ks-key-alias", "innerlock", "--ks-pass", f"file:{password_file.relative_to(PROJECT).as_posix()}",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--v4-signing-enabled", "false",
         "--out", apk, aligned], "Sign APK")
    run([*signer, "verify", "--verbose", "--print-certs", apk], "Verify APK signature")
    run([sdk / "zipalign.exe", "-c", "4", apk], "Verify APK alignment")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (dist / "Mate-X2-InnerScreen.apk.sha256").write_text(f"{digest}  {apk.name}\n", encoding="ascii")
    print(f"Built: {apk}\nSHA256: {digest}", flush=True)


if __name__ == "__main__":
    main()
