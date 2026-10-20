# 协议与开源材料维护说明

隐私政策、用户协议和开源介绍的 Android 应用内正文位于仓库根目录下的 `app/app/src/main/assets/legal/`，本目录（`release/docs/`）中的对应 Markdown 为便于 GitHub 阅读的副本，更新时应同步。涉及数据处理或协议的重要变化需更新 `ConsentStore.DOCUMENT_VERSION`、正文版本与日期，使下次进入再次主动确认。

隐私政策按本版本实际功能编写，包含本机数据、文件授权、可选系统/AI语音、WebDAV、更新检查、外部链接、权限、诊断和清理方式；用户协议不设置不合理的单方免责或“最终解释权”。制定时参考以下权威公开文本，本文不替代具体运营或发行场景下的专业审查：

- [中华人民共和国个人信息保护法](https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm)
- [中华人民共和国民法典](https://tjca.miit.gov.cn/zwgk/zcwj/flfg/art/2020/art_20cf1a2e1b854924b5caa744c8045d1f.html)

首次门禁独立于书库及网络初始化，保存隐私政策版本、协议版本和接受时间。Android 生命周期、音量键或外部打开 Intent 不得在门禁前创建 ReaderViewModel。后台 Worker 亦须检查同意版本，避免旧版排程绕过门禁。

第三方清单从 Gradle `releaseRuntimeClasspath` 解析，1.9.0 共111个坐标（含BOM）。许可证由对应POM与本地AAR/JAR提取核对；AndroidX与多数依赖适用Apache-2.0。下列需额外保留：

- [PDFBox-Android v2.0.27.0 LICENSE](https://github.com/TomRoush/PdfBox-Android/blob/v2.0.27.0/LICENSE.txt) 与 [NOTICE](https://github.com/TomRoush/PdfBox-Android/blob/v2.0.27.0/NOTICE.txt)，包含历史PDFBox/FontBox及Adobe等声明。
- [Bouncy Castle 1.72 原许可证](https://github.com/bcgit/bc-java/blob/r1rv72/LICENSE.html)，只移除HTML展示标签，保留原版权与许可正文。
- OkHttp 4.12.0 JAR 内 `okhttp3/internal/publicsuffix/NOTICE` 与 [MPL 2.0原文](https://www.mozilla.org/MPL/2.0/)，用于内含的Public Suffix List数据。
- PDFBox AAR中内置字体name表标记为LiberationSans-Regular 2.1.5、Google/Red Hat版权及SIL OFL1.1；保留 [Liberation Fonts 2.1.5许可证](https://github.com/liberationfonts/liberation-fonts/blob/2.1.5/LICENSE)。

离线许可证原文、上游声明及依赖清单位于 `app/app/src/main/assets/legal/licenses/`；本目录的 `licenses/` 为对应副本。项目本身的GPL-3.0以仓库根目录 [LICENSE](../../LICENSE) 为准。更换依赖或发布版本时应重新核对清单和版权声明。
