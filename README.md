# AniTV 1.1

面向 Android TV 的原生番剧客户端，支持 Android 8.0 及以上。

[下载 1.1 安装包](https://github.com/Emokui/AniTV/releases/download/native-v1.1/AniTV-1.1.apk) · [下载可构建源码](https://github.com/Emokui/AniTV/releases/download/native-v1.1/AniTV-1.1-source.zip) · [发布页面](https://github.com/Emokui/AniTV/releases/tag/native-v1.1)

![AniTV](anitv-banner-source.png)

## 功能

- 深色侧栏与海报网格，遥控器方向键浏览、确认切换页面。
- 推荐、索引、类型/语言/年份筛选、搜索、番剧介绍与相关系列。
- 带封面的选集窗口，右侧四分之一宽度线路面板，显示首字节延迟。
- MP4 使用系统 NuPlayer，HLS 使用 Media3；支持暂停、快进、自动下一集。
- 按番剧记忆手动选择的线路，下一集和重新进入时优先匹配。
- 历史位于收藏上方，短按进入介绍页，长按确认删除记录；保留上次剧集和进度。
- 正常账号登录、本机收藏、分类缓存清理；图片和接口缓存有上限。
- 剧集列表可从本地缓存先显示、后台更新；索引按三页窗口回收，保留向前回看能力。

UI 与资源独立实现，采用 BT 风格的深色布局和焦点效果，未使用 BT 反编译代码或资源。

## 遥控操作

| 按键 | 操作 |
| --- | --- |
| 侧栏确认 | 切换页面；确认当前页刷新 |
| 侧栏右键 | 进入当前页内容 |
| 索引中菜单 | 直接聚焦类型 / 语言 / 年份筛选栏 |
| 播放中确认 | 暂停 / 继续；进度条仅暂停时显示 |
| 播放中左右 | 单按约 10 秒，长按逐步加速至 30 / 60 秒 |
| 播放中上键 | 选集 |
| 播放中下键 / 菜单 | 选择线路 |
| 弹窗返回 | 关闭弹窗，恢复原播放状态 |
| 播放中返回 | 首次隐藏进度，5 秒内再按退出 |
| 历史海报长按确认 | 删除该条历史与续播进度 |

## 安装与版本关系

原生版包名为 `tvx.anich.preview`，应用显示名称 AniTV。1.1 可覆盖此前原生预览版和 1.0.x，保留其账号、收藏、历史和线路偏好。

1.1 是独立原生版的版本号，与旧 APK 补丁版 v1.9 属于不同版本序列。旧版 `tvx.anich` 可共存；两者账号与历史不自动迁移。[旧版 v1.9](https://github.com/Emokui/AniTV/releases/tag/v1.9) 和旧源码包继续保留。

## 性能与限制

海报按实际显示尺寸解码；图片资源缓存 16 MiB、位图池 8 MiB、图片磁盘缓存 64 MiB。接口缓存每目录最多 8 MiB / 24 文件；剧集内存缓存最多 8 部、合计 2000 集。网络与解析工作池分离，工作线程空闲 30 秒后回收。以上不是应用总内存上限。

首次未缓存的数据、真正刷新和播放线路仍依赖接口与网络。HTTP 首字节延迟不代表带宽或完整可播放性。不同电视、编解码格式和第三方线路的兼容性有差异；不宣称固定帧率或总内存降幅。

## 构建源码

下载 `AniTV-1.1-source.zip` 并解压，原生 Android 工程位于 `native-preview/`，包含 Java 源码、资源、测试、Gradle Wrapper 和测试素材生成脚本。

需要 JDK 17 或以上、Android SDK（platform 35 / Build Tools 35）以及可访问 Google Maven 和 Maven Central 的网络。配置 `JAVA_HOME` 和 `ANDROID_HOME` 后：

```bash
cd native-preview
bash build-native.sh
```

脚本首次构建会生成本地签名，之后复用。产物为 `app/build/outputs/apk/release/app-release.apk`。源码包不含发布私钥，自行构建的签名与发布包不同。

本地回归：121 项通过、1 项显式在线测试跳过，Debug / Release Lint 与 Release 构建通过。电视实际遥控和播放体验由用户验证。

## 来源

推荐、索引、搜索、详情和播放接入 AniCh 兼容接口。第三方服务、内容与组件保留各自权利；本项目不改变相关授权。旧补丁版基于 [AniCh](https://github.com/Sle2p/AniCh) 1.5.27，原生版是独立 Android 实现。
