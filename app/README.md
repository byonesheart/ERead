# E阅 Android

本目录是 Android 的 Gradle 工程根。模块仍为 `:app`，位于本目录下的 `app/`，即仓库中的 `app/app/`。

从仓库根目录构建：

```powershell
.\app\scripts\build.ps1
```

从本目录构建：

```powershell
.\scripts\build.ps1
```

Android 源码、开发脚本、测试及本地工具都留在本目录。常规 Gradle 构建结果位于模块的 `app/build/`；构建脚本将 APK 汇总到 [`../release/android/`](../release/android/)，发布源码压缩包也在发布步骤中生成至该目录。设备测试包保留在模块的构建目录。本地工具、缓存、`local.properties` 和 `build/` 不提交 Git。

软件功能与下载方式见 [`release/README.md`](../release/README.md)，共用说明见 [`release/docs/`](../release/docs/)。
