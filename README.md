# AniTV 原生 Android TV 客户端

AniTV 是一款面向 Android TV 的原生动画浏览和播放应用，采用遥控器优先的侧栏、索引筛选、海报卡片、剧集与线路选择界面。

## 1.0 首个公开版本

- 应用版本：1.0（Android versionCode 30108）
- Android：8.0 / API 26 及以上
- 安装包：[AniTV-1.0-30108.apk](AniTV-1.0-30108.apk)
- 完整可构建源码：[AniTV-1.0-source.zip](AniTV-1.0-source.zip)
- 许可证：MIT，详见 [LICENSE](LICENSE)

源码压缩包包含 `native-preview/` Gradle 工程，不包含签名私钥。解压后可使用 Android Studio 打开 `native-preview/`，或在 macOS/Linux 终端进入该目录执行：

```sh
bash build-native.sh assembleRelease
```

版本变更记录见 [CHANGELOG.md](CHANGELOG.md)。
