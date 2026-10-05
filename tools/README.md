# Local command-line toolchain

This app builds with Python 3.9+, Java, and Google's Android command-line tools. It does not require Android Studio, Gradle, or a global SDK installation.

From the project directory, run:

```powershell
& 'D:\anaconda3\python.exe' tools/setup_tools.py
& 'D:\anaconda3\python.exe' build.py
```

The setup script downloads pinned Windows x64 tools into the sibling `../tmp/innerlock-build-tools` directory. Use `--tools-root <directory>` on both scripts to select another local directory. No PATH, registry, or persistent environment setting is changed.

| Tool | Pinned release | Verification source |
| --- | --- | --- |
| Eclipse Temurin JDK | 17.0.20.1+1, Windows x64 | SHA-256 published by the official `adoptium/temurin17-binaries` GitHub release |
| Android Build Tools | 35.0.0, Windows | SHA-1 and size published in Google's `repository2-3.xml` |
| Android SDK Platform | API 35, revision 2 | SHA-1 and size published in Google's `repository2-3.xml` |

Exact official archive URLs, hashes, and sizes are pinned in `setup_tools.py` and recorded under the tools directory in `toolchain.json`. Existing extracted tools are reused; initial downloads are verified before extraction.

The build uses `aapt2`, `javac` targeting Java 8, `d8` with minimum API 26, `zipalign`, and `apksigner`. It sets target API 31 and version `1.0` / code `1`. Inputs are `AndroidManifest.xml`, `res/`, `src/**/*.java`, and optionally `assets/`. Output is `dist/Mate-X2-InnerScreen.apk` with a SHA-256 sidecar.

On the first build a personal RSA signing key and generated password are saved under `.local/`. Keep that directory private and backed up if you want future APKs to update the installed app. The password is passed to Java tools through a file and never printed. The signing material, build intermediates, and generated APKs are ignored by Git. The build script never invokes ADB or changes a connected device.
