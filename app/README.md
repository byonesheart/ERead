# E阅 Android

本目录是 Android 的 Gradle 工程根。模块仍为 `:app`，位于本目录下的 `app/`，即仓库中的 `app/app/`。

Android EPUB／MOBI 使用兼容引擎和系统 WebView 显示原书结构；1.11.4 已移除 Readium 运行依赖及切换入口。工具链保留 JDK 17、Gradle 9.7.0、Android Gradle Plugin 9.3.3、Kotlin／Compose Compiler 2.4.20、Android SDK 37.0（`platforms;android-37.0`）与 Build Tools 36.0.0；`minSdk` 26、`targetSdk` 35。不改变 `windows/` 的构建配置。

Compose BOM 固定为 2026.06.01，Lifecycle runtime／viewmodel-compose 固定为 2.11.0；生产与测试依赖保持一致。

从仓库根目录构建：

```powershell
.\app\scripts\build.ps1
```

从本目录构建：

```powershell
.\scripts\build.ps1
```

脚本将工具下载并校验到本目录 `.tools/`，不要求修改全局 Java／Android SDK。首次运行需要网络和 SDK 许可；后续复用已验证的本地工具。默认执行 Android 单元测试、Debug APK 和 lint。需要指定任务时，例如从仓库根目录运行：

```powershell
.\app\scripts\build.ps1 -Tasks @('testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest', 'lintDebug')
```

设备测试需要已授权的 Android 设备；执行设备任务前应确认测试变体与正式书库隔离。当前跳转、预加载及导入行为见 [1.11.6 说明](../release/docs/legacy-engine-1.11.6.md)，连贯滚动见 [1.11.5 说明](../release/docs/legacy-engine-1.11.5.md)，旧书库兼容边界见 [兼容引擎说明](../release/docs/legacy-engine-1.11.4.md)。[Readium 迁移说明](../release/docs/READIUM_MIGRATION.md)仅保留历史记录。

Android 源码、开发脚本、测试及本地工具都留在本目录。常规 Gradle 构建结果位于模块的 `app/build/`；构建脚本将 APK 汇总到 [`../release/android/`](../release/android/)，发布源码压缩包也在发布步骤中生成至该目录。设备测试包保留在模块的构建目录。本地工具、缓存、`local.properties` 和 `build/` 不提交 Git。

软件功能与下载方式见 [`release/README.md`](../release/README.md)，共用说明见 [`release/docs/`](../release/docs/)。
