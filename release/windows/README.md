# Windows 发布产物

本目录预留给未来的 Windows 安装包、便携包及对应源码压缩包。目前没有可用的 Windows 安装包，开发入口见 [`../../windows/`](../../windows/)。

Windows 版本号、支持的系统和下载入口在实际开发、验证并发布后确定；发布说明应与可下载的 Windows 文件一致。本目录中的本地文件不会自动发布。

现有 [`../update.json`](../update.json) 和仓库根的 `update.json` 服务于 Android。Windows 的更新方式与清单应在实现时单独确定，不将 Android 更新清单复制为 Windows 更新清单。

未来的安装包和源码压缩包只在本地保留，不纳入 Git；本目录仅提交此 `README.md`。
