# GitHub 源码与版本发布

仓库：https://github.com/byonesheart/ERead

## 上传源码

项目使用仓库中已有的 GPL v3 许可证。源码、测试、构建脚本、说明、作者头像及作者提供的赞赏码可一起提交；`.tools/`、构建目录、安装包、个人书籍、密钥和本机路径配置由 `.gitignore` 排除。

在项目根目录打开 PowerShell：

```powershell
git status
git push -u origin main
```

首次推送若没有 GitHub 登录记录，Git Credential Manager 会打开浏览器。需要你本人登录 **byonesheart** 并完成 GitHub 授权或双重验证；不要把密码、验证码或访问令牌粘贴到聊天、源码或远程地址中。之后可复用系统凭据管理器中的登录记录。

后续修改的正常提交方式：

```powershell
git add .
git commit -m "Describe the change"
git push
```

推送前检查 `git status` 和暂存差异，确认未包含自己的文件及凭据。安装包使用 Releases 发布，无需提交到 Git 历史。

## 发布可检查的新版本

软件“关于”中的“检查更新”只在点击后请求：

https://raw.githubusercontent.com/byonesheart/ERead/main/update.json

版本依据 Android 的 `versionCode` 比较，不自动下载或安装。先准备并测试对应安装包，再发布更新信息：

1. 在 `app/build.gradle.kts` 增加 `versionCode`，设置 `versionName`。
2. 执行 `scripts/build.ps1`，验证生成的 `artifacts/ERead-版本-debug.apk`。
3. 打开 [创建 Release](https://github.com/byonesheart/ERead/releases/new)，填写标签（例如 `v1.9.5`）、名称及核心更新日志，上传对应 APK 后发布，同时更新网盘内的安装包。
4. 更新根目录 `update.json` 中的版本、Release 页面、APK 下载链接和 `mirrors` 网盘链接及提取码，提交并推送。链接中的标签和文件名必须与 Release 实际内容一致。

当前 `1.9.7` 对应标签为 `v1.9.7`，附件名为 `ERead-1.9.7-debug.apk`。发布前可先保存 Release 草稿；不要让更新文件指向尚未上传的安装包。

本版清单见 [update-1.9.7.json](releases/update-1.9.7.json)，根目录 `update.json` 与其保持一致。发布时先上传安装包、源码包并发布 Release，确认附件完整后，再推送主分支的新清单，避免提前宣告尚未发布的安装包。GitHub 登录授权有效时，可自动完成这套流程。网盘使用已授权的上传分享工具，将 APK 存入百度和夸克的 `/项目/E阅`，百度提取码为 `READ`，校验实际分享结果后同步 README、Release 和更新清单。

`changelog` 只写核心功能变化，并以“更多功能请自行体验。”结尾；软件内通过「更新日志」按钮进入本次更新页。检查更新接受本仓库的 HTTPS Release 页面和 APK 链接，以及百度网盘、夸克网盘的 HTTPS 分享链接，不自动下载或安装。

## 安装签名

目前提供调试签名 APK。现有设备覆盖安装需要使用相同签名；换电脑或使用 CI 新生成的调试密钥，可能无法覆盖以前安装的版本。请私下保留原签名密钥；任何签名私钥、密码或凭据都不要上传至公开仓库。未来改用发布签名时，需另行安排安装迁移。
