# AniTV

Android TV adaptation patches for [AniCh](https://github.com/Sle2p/AniCh) 1.5.27. Android 8.0+ / ARMv7. Application: **AniTV**, package: `tvx.anich`. Coexists with the original app.

![AniTV](anitv-banner-source.png)

## Download

Get the latest APK from [Releases](https://github.com/Emokui/AniTV/releases). Current patch: **v1.9**, based on AniCh **1.5.27**.

## Features

- Android MediaPlayer + SurfaceView hardware playback, adapted for green-screen and audio problems on tested Hisense TVs.
- Original right-side episode and source selection panels. No on-screen danmaku.
- Episode / Character / Recommendation detail tabs; Episodes selected and focused by default.
- Removed Recently Updated home tab; fixed index filters; Back from index grid returns to filters.
- Press Back twice within two seconds on the home screen to exit.
- App-only density/font adjustment, lighter card shadows, 48 MiB Skia resource cache cap, 300ms to 100ms tab transitions.
- Minimal AniTV wordmark and separate TV banner.

## Remote controls

| Key | During playback |
| --- | --- |
| OK | Pause / resume |
| Left / Right | Seek about 10 seconds |
| Up | Open episode sidebar |
| Down / Menu | Open source sidebar |
| Back in sidebar | Cancel and resume current video / position |
| Back during playback | Exit playback |

## Scope and limitations

This is an APK adaptation patch, **not a recovered full Flutter project** or an official release. Upstream: [Sle2p/AniCh](https://github.com/Sle2p/AniCh), [official releases](https://github.com/Sle2p/AniCh/releases). Original application and third-party components retain their respective rights and licenses; this repository does not relicense them.

MP4 uses HTTP Range MediaDataSource with NuPlayer. HLS still uses the system URL playback path and some sources fail. No automatic next episode. Original playback settings are not all connected to the native player. Hardware video layers may be absent in ADB screenshots. Index services may return HTTP 500. Shorter transitions and cache limits are not a measured FPS or total-memory improvement.

Download and extract `AniTV-v1.9-source.zip` for the complete project directory tree. The paths below are inside that bundle.

## Contents

- `patch/java`: player, data source, sidebar and remote state sources.
- `patch/smali`: modified original APK classes.
- `patch/*.c`, `*.map`: JNI bridge and mpv proxy.
- `patch/patch_flutter_ui.py`: verified ARM32 Flutter AOT modifications.
- `patch/patch_apk.py`: verified APK repackaging.
- `patch/classes.dex`, `*.so`: prebuilt patch components.
- `patch/branding`, `design`: launcher assets, originals and image prompts.
- `build-scripts`: reference local workflows; configure tools, inputs and temporary paths.
- `CHANGELOG.md`: detailed changes, tests and limitations (Chinese).

## Repackage

Requires Python 3, Android SDK Build Tools and the exact original ARMv7 APK:

```text
anich-android-armeabi-v7a-1.5.27.apk
SHA256 f35f1ca9755e0a13486e8c030139ef3a75a609abd061419c19a5a362b0b45069
```

Using the prebuilt DEX / SO components:

```sh
mkdir -p build
python3 patch/patch_flutter_ui.py original.apk build/libapp.so
python3 patch/patch_apk.py original.apk build/unsigned.apk --shim patch/libmpv.so --dex patch/classes.dex --bridge patch/libanichtvbridge.so --app build/libapp.so --branding patch/branding
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks /path/to/your-keystore.jks --out build/AniTV.apk build/aligned.apk
apksigner verify --verbose build/AniTV.apk
```

Java rebuilds require JDK 17, Android SDK, apktool and a decoded working directory prepared with `patch/smali`. Native bridges use Zig 0.13.0. Reference scripts accept `ANITV_PROJECT`, `JAVA_HOME`, `ANDROID_HOME` paths.

Signing keys, passwords, TV connection details and runtime logs are excluded. Self-signed builds have a different signature from published APKs and normally cannot update them in place; preserve your data and signing keys.
