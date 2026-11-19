# Android TV 构建说明

需要 JDK 17 或以上、Android SDK Platform 35 和 Build Tools 35。将 `JAVA_HOME` 和 `ANDROID_HOME` 指向相应安装目录，使用随工程提供的 Gradle Wrapper。以下命令从仓库根目录执行。

```sh
bash android/build-native.sh assembleRelease testDebugUnitTest lintRelease compileDebugAndroidTestJavaWithJavac
```

Release APK 位于 `android/app/build/outputs/apk/release/app-release.apk`。也可以在 Android Studio 打开 `android/`。

脚本使用 `android/signing/anitv.keystore` 签名。如果没有密钥，首次运行会生成本机开发密钥。自行构建的 APK 需要使用与已安装版本相同的证书才可覆盖安装。Git 仓库仅收录源码、构建配置、测试和必要资源。

设备播放测试所需的本地样片可用 FFmpeg 生成。配置好电视测试环境后执行：

```sh
bash android/tools/generate-playback-fixtures.sh
bash android/build-native.sh connectedDebugAndroidTest
```
