# Android 发布产物

本目录汇总 Android 安装包（APK／AAB）及对应版本的源码压缩包。Android 工程位于 [`../../app/`](../../app/)，构建脚本将 APK 汇总到本目录，发布用源码压缩包也统一存放于此。

下载入口和软件说明见 [`../README.md`](../README.md)。正式下载以发布页面或网盘中实际提供的文件为准，本地文件不会因放入本目录而自动发布。

发布时核对安装包的版本名、版本码与对应源码版本，再同步 [`../update.json`](../update.json) 和供旧 Android 客户端读取的 [仓库根 `update.json`](../../update.json)。两个清单的 Android 版本和下载信息应保持一致。

安装包和源码压缩包只在本地保留，不纳入 Git；本目录仅提交此 `README.md`。
