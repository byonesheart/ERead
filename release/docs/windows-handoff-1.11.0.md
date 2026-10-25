# 1.11.0 Android EPUB 引擎迁移交接（本地验证版）

> 1.11.6 更新：Android 新 EPUB 导入使用内容版本 9，章节展示缓存增加可选 `archivePath`。该缓存不是跨端协议；WebDAV 仍保存原始 EPUB 和已物化的规范文本／样式。书库版本、章节／UTF-16 位置规则不变，Windows 无需采用 Android 的 WebView 预加载实现。详见 [1.11.6 说明](legacy-engine-1.11.6.md)。

> 1.11.5 更新：Android 新增默认开启的 EPUB／MOBI 连贯滚动，配置字段 `continuousEbookScroll` 可选、缺省 `true`。书库和 WebDAV 协议不变，仍以章节／UTF-16 字符保存进度；Windows 保留该字段即可，无需采用 Android 的渲染实现。详见 [1.11.5 说明](legacy-engine-1.11.5.md)。

> 1.11.4 更新：Readium Toolkit 和 Android 引擎选择入口已移除，统一回到原书结构的兼容引擎；后续开发以 [1.11.4 兼容引擎说明](legacy-engine-1.11.4.md)为准。下文为历史迁移交接。书库和 WebDAV 协议未升级，历史 `readium-publication.json` 仍需保留，Windows 无需改用 Readium。

本轮仅修改 Android，未修改或验收 `windows/` 的既有开发工作。按作者要求暂不发布 GitHub 或网盘，真机验收已中止；具体电脑端验证结果见 [验证记录](verification.md)。

Android EPUB 改用 Readium Kotlin Toolkit 3.4.0 的 PublicationOpener 与 EpubNavigatorFragment。它们是 Android 组件，不能直接作为 Windows 渲染器使用。TXT、PDF、MOBI 保留原链路；Android 旧 EPUB 引擎仍作为兼容入口。完整边界见 [迁移说明](READIUM_MIGRATION.md)。

后续 1.11.2 本地修复仅调整 Android Fragment 生命周期与兼容 WebView 的页面初始化／首报时机，未升级书库、索引或 WebDAV 协议，Windows 无需新增数据迁移。原位置须在首次有效 viewport 上报前恢复，空白页／图片封面也须主动报告就绪；这是其他端接入浏览器渲染时应保留的行为。

1.11.3 同样没有跨端协议变更。Android 的 MOBI 展示缓存按章节生成，WebView 跨章复用；章节末尾补足物理视口，避免滚动上限裁去右边距。Windows 若复用 HTML 渲染思路，应核对末页几何位置，并从可见文本节点构造引文，避免将隐藏样式、脚本或注释写入定位上下文。原字体失效的回退显示与原字体资源修复须分别验收。

## 共享数据与同步

- 本地 `library.json` 仍为 version 13，WebDAV 数据协议仍为 version 1；没有批量重导入或重编号已有 EPUB。原书籍、章节、标注 ID 和云端身份继续保留。
- 新 EPUB 完整保存在私有 `source.epub`，辅助文字索引使用 contentVersion 8。新索引以原书 spine 资源为单位，原书目录可能在同一资源内有多个小节；不要假设目录项数量等于正文缓存章节数量。
- 旧书签、笔记和划线继续保留原章节与 UTF-16 偏移；不是 Unicode 码点或字节偏移。Windows 可继续使用这些兼容记录，不应覆盖成 DOM 偏移。
- 新增可选 `readium-publication.json`，保存 Readium manifest、spine href、章节 ID 和索引版本。WebDAV 将其作为 `assets/readium-publication.json` 保存和还原。Windows 即使暂不使用，也应保留该辅助文件，避免 Android 在还原后失去资源与旧坐标的快速映射。
- Readium Locator 位于 Android 的独立 `readium-locators/` 附加文件中，带内容版本；当前位置合并保存，不重写整本书架。它们暂不作为跨端阅读记录协议。跨端仍以兼容的章节／UTF-16 位置为准，不能声称 Windows 已直接使用 Readium Locator。
- `ReaderSettings` 新增可选 `epubEngine`：`READIUM` 或 `LEGACY`，缺省为 `READIUM`。Windows 无需据此切换渲染器；保留不识别的设置字段，避免往返同步时抹掉 Android 的偏好。

## 渲染和功能边界

原书 HTML、CSS、XML、图片、内嵌与混淆字体由 Readium 容器加载，兼容文字投影只用于搜索、旧标注和朗读定位。不要以投影文本替换原归档或据此重新生成书籍。

Readium 目录保留全部锚点和嵌套层级；语音缓存章节管理使用正文资源索引。一个资源中的多个目录小节可能共用语音缓存；旧书存在一个资源对应多个旧章节时，不猜测缓存徽标的归属。

Android 的上下翻页、覆盖／仿真效果、简繁转换、图片背景、未保存编辑预览和已有图片批注目前通过旧引擎保留。Readium 路径的新建图片长按批注尚未接入；CJK／RTL 和原书样式下，部分间距偏好受 Readium CSS 限制。重复引用同一 spine href 的独立 Locator 定位也有上游限制。

Android 工具链升级至 Gradle 9.7.0、AGP 9.3.3、Kotlin 2.4.20、编译 SDK 37.0、Lifecycle 2.11.0、Compose BOM 2026.06.01。Windows 工程没有随之升级；两端的构建依赖应继续独立管理。
