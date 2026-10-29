# 开源相关

E阅使用 Kotlin 与 Android Jetpack Compose 开发。项目源码遵循仓库随附的 GNU General Public License v3.0（GPL-3.0）；各第三方组件仍遵循各自许可证，作者头像、赞赏码及用户导入内容不由此获得新的授权。

源码仓库：https://github.com/byonesheart/ERead

## 简繁转换词库

OpenCC 1.1.9 的 STCharacters、STPhrases、TSCharacters、TSPhrases 词库用于离线简繁体阅读显示转换。词库原样随软件分发，许可证：Apache-2.0，原许可证保留在下方。该功能不修改源书籍。

项目：https://github.com/BYVoid/OpenCC

## 内置阅读字体

宋体、黑体使用 Adobe 思源宋体 CN Regular、思源黑体 CN Regular 的官方地区子集，保留原始字体数据、版权及 SIL Open Font License 1.1 许可。用于固定阅读字形，不随手机系统主题更换。字体来源与校验信息见仓库 `release/docs/fonts.md`，原许可证可在软件内离线查看。

思源宋体：https://github.com/adobe-fonts/source-han-serif

思源黑体：https://github.com/adobe-fonts/source-han-sans

## EPUB／MOBI 阅读

EPUB 与未加密 MOBI 由本地兼容引擎读取，使用 Android 系统 WebView 显示原书 HTML、CSS、图片和字体。阅读索引用于目录、搜索、标注及朗读定位；保留完整原始文件。E阅不再打包 Readium Toolkit、Readium CSS 或其字体资源。

出版物背景与正文分层、受保护区域的配色处理及部分阅读背景配色，参考 7Mrlan 提供的 ebook v3.0.0+build.66 源码（GPL-3.0）进行适配；仍使用 E阅现有阅读及定位链路。

## 界面与 Android 基础组件

AndroidX Activity、Compose UI / Foundation / Runtime、Material 3、Material Icons：用于页面、手势和图标。项目以 Compose BOM 管理对应版本，最终运行时版本由 Gradle 依赖解析确定。许可证：Apache-2.0。

AndroidX Lifecycle：界面生命周期与状态管理；WorkManager：后台任务；ExifInterface：图片方向信息。以及 AndroidX Core、SavedState、Room、SQLite、DocumentFile 等运行时依赖。许可证：Apache-2.0。

项目：https://android.googlesource.com/platform/frameworks/support

Android 系统提供的 WebView（基于 Chromium）用于 EPUB／MOBI 的 HTML、CSS 和图文排版。E阅不额外打包 Chromium；具体组件版本和许可信息由设备上的 WebView 提供。

说明：https://developer.android.com/reference/android/webkit/WebView

## Kotlin 与协程

Android 构建工具链使用 Kotlin／Compose Compiler 2.4.20。Kotlin 标准库、kotlinx.coroutines、kotlinx.serialization 与 JetBrains Annotations 用于运行时、异步工作及数据处理，其最终运行时版本由 Gradle 依赖解析确定。许可证：Apache-2.0。

项目：https://github.com/JetBrains/kotlin

协程：https://github.com/Kotlin/kotlinx.coroutines

序列化：[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)，许可证为 Apache-2.0。

## 网络与数据流

OkHttp 4.12.0 与 Okio 3.6.0：WebDAV、在线语音及更新检查网络请求和流式读写。许可证：Apache-2.0。OkHttp 内含 Public Suffix List 数据，适用 Mozilla Public License 2.0，相应 NOTICE 与许可证在下方保留。

项目：https://github.com/square/okhttp

Okio：https://github.com/square/okio

## 辅助依赖

JSpecify 空值规范注解和 Guava ListenableFuture 冲突规避占位模块适用 Apache-2.0。精确版本、Maven 坐标、POM 和许可地址见 `runtime-dependencies.txt`，原文见 `Apache-2.0.txt`。

## PDF 与密码学依赖

PDFBox-Android 2.0.27.0：PDF 文本处理，移植自 Apache PDFBox / FontBox。许可证：Apache-2.0，并保留上游 NOTICE、原 PDFBox / FontBox、Adobe 等附加授权声明。

项目：https://github.com/TomRoush/PdfBox-Android

Bouncy Castle bcprov / bcpkix / bcutil 1.72：PDFBox 引入的密码学运行时依赖。许可证：Bouncy Castle Licence（MIT 风格），下方提供原文。

项目：https://github.com/bcgit/bc-java

PDFBox 内置 Liberation Sans Regular 2.1.5 字体，版权归 Google Corporation 与 Red Hat, Inc.，适用 SIL Open Font License 1.1。该字体用于 PDF 处理，不代表软件向用户提供额外书籍内容。

项目：https://github.com/liberationfonts/liberation-fonts

## 清单与声明

随附 `runtime-dependencies.txt` 依据 E阅 1.11.4 本轮 `releaseRuntimeClasspath` 实际解析结果生成，共 130 个唯一 Maven 坐标，其中含 3 个版本管理 BOM。清单列出每项的版本、POM、许可证及项目地址；必要时许可按父 POM 继承，未确认的字段会标记为 unknown。坐标数量不代表安装包内二进制文件数量，依赖解析也不代表功能已经通过设备验证。

Gradle / Android Gradle Plugin、JUnit、Robolectric、Espresso 等仅用于构建或测试的工具，不作为正式安装包内的功能组件列出。Android 平台提供的 PDF 渲染、系统 TTS、媒体控制与 Keystore 能力由系统实现，不单独随本软件分发系统引擎。

感谢各开源项目及其贡献者。下方提供项目许可证原文、PDFBox 与 OkHttp 上游声明、字体许可证及运行时依赖清单，便于离线查阅；文件内的版权和许可文本保留原文。
