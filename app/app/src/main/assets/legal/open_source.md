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

## 界面与 Android 基础组件

AndroidX Activity 1.10.1、Compose UI / Foundation / Runtime 1.8.2、Material 3 1.3.2、Material Icons 1.7.8：用于页面、排版、手势和图标。Compose BOM 2025.05.01 管理对应版本。许可证：Apache-2.0。

AndroidX Lifecycle 2.9.0：界面生命周期与状态管理；WorkManager 2.10.1：后台任务；ExifInterface 1.4.1：图片方向信息。以及 AndroidX Core、SavedState、Room、SQLite、DocumentFile 等运行时依赖。许可证：Apache-2.0。

项目：https://android.googlesource.com/platform/frameworks/support

## Kotlin 与协程

Kotlin 标准库 2.1.21（兼容层 jdk7 / jdk8 1.9.10）；kotlinx.coroutines 1.10.2；运行时引入 kotlinx.serialization 1.7.3 与 JetBrains Annotations 23.0.0。用于 Kotlin 运行时、异步工作与相关基础能力。许可证：Apache-2.0。

项目：https://github.com/JetBrains/kotlin

协程：https://github.com/Kotlin/kotlinx.coroutines

## 网络与数据流

OkHttp 4.12.0 与 Okio 3.6.0：WebDAV、在线语音及更新检查网络请求和流式读写。许可证：Apache-2.0。OkHttp 内含 Public Suffix List 数据，适用 Mozilla Public License 2.0，相应 NOTICE 与许可证在下方保留。

项目：https://github.com/square/okhttp

Okio：https://github.com/square/okio

## PDF 与密码学依赖

PDFBox-Android 2.0.27.0：PDF 文本处理，移植自 Apache PDFBox / FontBox。许可证：Apache-2.0，并保留上游 NOTICE、原 PDFBox / FontBox、Adobe 等附加授权声明。

项目：https://github.com/TomRoush/PdfBox-Android

Bouncy Castle bcprov / bcpkix / bcutil 1.72：PDFBox 引入的密码学运行时依赖。许可证：Bouncy Castle Licence（MIT 风格），下方提供原文。

项目：https://github.com/bcgit/bc-java

PDFBox 内置 Liberation Sans Regular 2.1.5 字体，版权归 Google Corporation 与 Red Hat, Inc.，适用 SIL Open Font License 1.1。该字体用于 PDF 处理，不代表软件向用户提供额外书籍内容。

项目：https://github.com/liberationfonts/liberation-fonts

## 清单与声明

下方运行时依赖清单依据 1.9.0 releaseRuntimeClasspath 解析生成，列出 111 个依赖坐标及对应版本、POM 许可证和项目地址（其中含依赖版本管理 BOM，不代表每项均包含二进制代码）。JSpecify 1.0.0 与 Guava ListenableFuture 1.0 等基础依赖也列于其中。

Gradle / Android Gradle Plugin、JUnit、Robolectric、Espresso 等仅用于构建或测试的工具，不作为正式安装包内的功能组件列出。Android 平台提供的 PDF 渲染、系统 TTS、媒体控制与 Keystore 能力由系统实现，不单独随本软件分发系统引擎。

感谢各开源项目及其贡献者。下方提供项目许可证原文、PDFBox 与 OkHttp 上游声明、字体许可证及运行时依赖清单，便于离线查阅；文件内的版权和许可文本保留原文。
