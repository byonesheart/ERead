# Windows 增量交接：Android 1.10.3

更新日期：2026-10-24。本次 Android 开发基线为 **1.10.3 / versionCode 42**，源码标签 `v1.10.3`，发布入口为 [1.10.3 Release](https://github.com/byonesheart/ERead/releases/tag/v1.10.3)。本文接续 [完整交接](windows-handoff.md) 和 [1.10.2 增量交接](windows-handoff-1.10.2.md)；最终构建、设备验证及发布状态，以 [验证记录](verification.md) 为准。本文没有修改或验收 Windows 应用。

## EPUB／MOBI 原书 DOM 阅读路径

EPUB／MOBI 小说有原文件引用时，改由 Android WebView 直接排版原 HTML 与书内资源；不再先把当前章投影成原生文字样式，再用 Compose `TextMeasurer` 分页。正文提取仍负责导入、搜索、编辑、朗读与稳定坐标，不能宣传为“完全不解析文件”。TXT 保持原生文本测量和按章分页；PDF／图片／漫画以及无原文件引用的示例继续原有路径。原件缺失、读取失败或与规范正文不一致会明确提示，不用空白正文或静默切回另一引擎掩盖错误。

### 文件职责与有界缓存

路径相对 `app/app/src/main/`：

| 文件 | 职责 |
| --- | --- |
| `java/com/eyue/reader/data/EbookSourceDocument.kt` | `EbookSourceStore` 按章提供原 HTML、资源基址、注释目标；`openResource` 只开放本书资源，关闭时释放流 |
| `java/com/eyue/reader/data/EbookHtmlStructure.kt` | 保存原 HTML 包装、章节切分、目录及 MOBI 兼容结构 |
| `java/com/eyue/reader/ui/EbookReaderHtml.kt` | 注入受信任阅读脚本和 CSP，保留正文 DOM／CSS，移除原书脚本、事件属性及可执行嵌入 |
| `java/com/eyue/reader/ui/EbookReaderSurface.kt` | WebView 生命周期、字体／资源拦截、JSON 桥、选区菜单与共享阅读回调 |
| `assets/reader/ebook-reader.js` | DOM 与规范正文映射、当前视口／本章页码、三模式、选区、装饰、高亮和脚注命中 |
| `java/com/eyue/reader/ui/EbookPageTurn.kt` | 原 DOM 页面的覆盖／滑动／仿真翻页过渡；墨水屏避免动画 |
| `java/com/eyue/reader/ui/ReaderScreen.kt` | 选择引擎，复用目录、工具栏、进度、书签、笔记、搜索、编辑及朗读会话 |

首次读取需要建立原结构与资源索引，位于 `cacheDir/ebook-source/<bookKey>/<来源身份摘要>/`；后续打开可信未变原件复用持久缓存，不反复完整解包。缓存身份包含来源和章节结构，仍须校验可读取原件。EPUB 资源按需从归档读取，MOBI 复用已提取或按需准备的资源；缓存不是第二份可脱离来源的正式书籍备份。

普通本地文件和可定位的文档描述符直接读取。若来源提供器只支持顺序流，阅读会话复用一份受控临时文件，避免每个 CSS、图片或字体都复制整书；并发资源读取共用该副本，关闭书籍后删除。关闭界面不等待正在复制或解析的提供器，进行中的读取退出时负责补清理。Windows 若有类似流式来源，也需区分持久索引与会话临时原件。

内存中的原 DOM 文档最多保留当前及相邻章节，`retainWindow(center)` 不被预取重新定位；ReaderScreen 的规范正文同样仅保留当前 ±1，未读全书仅有轻量索引。当前页面就绪后预热已取得规范正文的邻章 HTML，不提前建立邻章 WebView。当前浏览器只排版当前章，以其布局几何计算“本章当前页／总页数”，不遍历所有页的字符范围，不恢复全书页表，也不把浏览器像素页表作为跨端缓存。首次打开长章、字体变化仍有该章浏览器布局与原文映射成本。

### 样式、坐标与共享功能

- 原书 DOM 路径不由“使用原书 CSS”开关决定；开关仅决定采用作者 CSS 还是软件正文格式，选中字体始终由软件替换。关闭时普通正文按软件字号、间距等显示；标题、引用等保留原字号比例、对齐、粗斜体与缩进，原表格、图片及嵌套结构保留。开启时采用原书 CSS 排版。
- 滚动、左右翻页、上下翻页均可用，左右翻页有三种效果，宽屏支持双页；四边边距、背景和简繁显示共用阅读设置。仅布局条件改变才重排，模式／CSS 改变由 DOM 保留当前锚点。
- 存储坐标仍为**原规范正文 UTF-16**。JS 建立原 DOM、简繁显示与规范正文映射，选择、搜索、高亮和朗读回调都返回规范偏移；`pageIndex` 只是本次布局提示。不要用 DOM 文本节点序号、像素位置或转换后字符下标覆盖已有记录。
- `EbookReaderController.navigate` 带预期章节，章加载完成后才应用；`checkpoint` 只暂存当前位置，供简繁等 DOM 重建时恢复，不与 CSS／模式重新配置同时重复跳转。过期 generation、未 ready 和读取错误不能提交零位置。
- `noteTargets` 保存原链接目标到释文 HTML 的映射，MOBI `filepos` 转为本地锚点；没有链接元数据时，ReaderScreen 只扫描当前及邻章规范正文中的确定数字释文。原书注释与用户笔记仍是独立数据。
- 未保存编辑通过规范正文投影到 HTML 文本节点，仍保留私有预览／手动保存及源文件写回规则；不因浏览器显示改变保存与云端版本语义。

### 本地桥消息：桌面实现需对齐的语义

这不是新云端协议。原 JS 只由阅读器注入，原书脚本不可信；Windows 使用另一 WebView 时仍须限制资源与网络，不把 Android 的 JavaScriptInterface 任意暴露给书内脚本。

| 方向 | 消息与字段 |
| --- | --- |
| 宿主初始化 | `ERead.init`：`canonical`、`config`、`images[{offset,path}]`、`noteTargets` 目标键列表、`notes[{start,end,label,content}]`、`decorations` |
| 宿主命令 | `navigate{offset,image,lastPage,ensureVisible}`、`move{direction}`、`page{page}`、`configure{config}`、`decorate{decorations}`、`notes{notes}`、`clearSelection`、`report` |
| JS → 宿主 | `ready`／`error{message}`；`viewport{offset,endOffset,page,pages,images,scrolling,atStart,atEnd}`；`turn{page,direction,style}`；`manualStart`／`manualEnd`／`chapterStep{direction}`／`tap` |
| 选区／标注 | `selection{start,end,page,image,left,top,right,bottom}`；`annotation{ids,...矩形}`；`footnote{target,content,label,offset,...矩形}`；偏移为规范 UTF-16，矩形为 CSS 像素，宿主转换为窗口坐标 |
| 显示转换 | `convert(text)` 返回 `{text,forward}`，映射至显示文本；不改源正文和保存坐标 |

书内资源使用 `https://eread.local/<bookKey>/archive/...` 受控基址；字体另用 `https://eread.local/reader-font?v=<fontVersion>`，字体切换更换版本参数，避免 WebView 复用上一字体缓存。不得将这些本地虚拟 URL 上传为跨端路径；桌面应重新映射本机资源。`noteTargets` 的内容先按 HTML 转成可读释文，不能把它当成可任意执行的页面。

章节是否读到底以真实滚动几何的 `atEnd` 判断；不能要求视口末字等于规范正文长度，因为作者 CSS 可能隐藏章末注释定义。行尾采用 UTF-16 右开区间，可见性检查使用末尾前一个字，避免朗读将实际在屏内的末字误认为不可见。

MOBI 仍沿用既有 PalmDOC／HUFF-CDIC 和混合文件的兼容 MOBI 段。本次改的是阅读渲染路径，**没有新增纯 KF8／AZW3 或 DRM 支持**。本地 `library.json` 仍为 version 13，WebDAV catalog／manifest／归档 metadata 仍为 version 1；不增加跨端 schema。

## 阅读配置入口

界面选项收紧为单行短按钮并保持可读，模式在背景下一行，简繁选择在字体下方。不能通过截断文本或缩成不可读字号实现“紧凑”；横向空间不足时可横向滚动。配置仍自动保存，小说／漫画独立，未改变原有设置字段。

## 清晰像素图标

`ui/AppIcon.kt` 中，现用的 58 个功能图标改为手工定义的 16×16 像素字模，以纯色网格和最近邻绘制显示。未知图标仅作二值轮廓回退，避免把矢量抗锯齿灰边放大；经典主题继续使用原矢量。保持状态着色、RTL 镜像、点击区域与无障碍说明。Windows 应采用清晰的整数网格笔画，不能只把原图标模糊缩小再放大。

## 书名与云端任务

- 书架网格和列表中的书名支持长按重命名。修改书架显示名称，不改外部源文件名或原书内部元数据；书籍 ID、正文与阅读记录保持原关联。未保存正文编辑存在时先处理编辑，再重命名。
- 名称参与备份变化判断，后续手动更新或符合既有自动备份条件时上传新名称；并非重命名后立即强制上传。云端占位书在下载正文后保留本地重命名，并保留待更新状态，不能误记为云端已有新名称。
- 同名上传的选择确认后，将选择快照交给后台任务并关闭窗口；处理失败仍需报告。关闭的是已完成的选择界面，不取消实际任务，也不改变原有同名冲突和计费网络判断。

## 搜索定位与返回

搜索提示为“双击进入结果位置”。进入结果前保存原章节、正文偏移及图片位置，提供“返回原位置”入口。定位本身不清除该入口；首次主动移动、跳页或跳章后清除。该临时返回锚点只属于本次搜索导航，不存入阅读记录，不与进度条的返回按钮混用。

## 数字注释的章内回退

`data/LazyChapterContent.kt` 保留原生路径的链接注释优先规则；原 DOM 路径在 `ReaderScreen.kt` 使用同一 `txtSourceFootnotes` 确定规则，不额外加载原生样式。当 EPUB／MOBI 当前章没有可用链接注释时，对规范正文尝试 `[n]` 与后续同号注释定义的章内回退；兼容数字标签与释文紧接的情况，不把所有普通数字引用猜成脚注。TXT 保持原有按定义块识别规则。

本机 prepared 的 **`sourceFootnotesVersion` 升为 3**，CSS 投影仍为 1。旧投影按需重建，不改正文、用户编辑或 UTF-16 锚点，不要求全书重新导入。`library.json` 仍为 version 13，WebDAV catalog／manifest／归档 metadata 仍为 version 1；本次不增加跨端协议版本。

原《西游记》EPUB／MOBI 已用于原结构、规范文本、链接目标及点击脚注的桌面回归，也覆盖“链接元数据丢失、正文仍有编号释文”的场景。桌面结果不能代替用户手机重现，不能宣称已穷尽厂商 WebView 差异。私人书籍及从原书导出的 HTML／释文／截图不得随源码或测试提交。

## 快速滚动与刷新率

- `ReaderRefreshRate.kt` 在当前物理分辨率的可用模式中优先请求 120 Hz；没有 120 Hz 档时使用可用的高刷新率，低刷新率设备使用自身支持值，不强制切换分辨率。墨水屏模式交还系统选择。
- 该请求不等于保证每秒 120 帧。实际刷新率和帧率仍受屏幕能力、系统省电／温控策略及应用每帧工作量影响，不能把 60 Hz 设备表述成已支持实际 120 帧。
- 阅读界面缓存轻量章计数与累计位置，复用最近的正文命中布局，限制布局缓存数量；正文、字体或视口等变化时失效。保持当前及相邻章节的有界数据窗口，不为滑动速度恢复整书正文或全书页表。

Windows 不应直接复制 Android 窗口刷新率 API；应沿用桌面绘制时钟，实测高刷新率屏幕与长章滚动。布局缓存同样必须有界，并按正文版本和排版条件失效。

## 验证范围

`app/scripts/test-ebook-reader.mjs` 的完整桌面 Edge 浏览器回归已有 **26 项通过、0 失败**记录，覆盖原 CSS、软件正文与特殊段落、三种阅读模式、双页、触摸／翻页消息、模式切换锚点、选区 UTF-16、简繁、字体切换、可见末字与章末几何、滚动边界、触摸取消、图片及真实 EPUB／MOBI 脚注。数据层／安全和元数据用例参考 `EbookSourceDocumentRobolectricTest`、`EbookHtmlStructureTest`、`EbookReaderHtmlRobolectricTest`、`ReaderEbookMetadataTest` 以及原书样本回归；最终测试总数、脚本版本和构建产物以验证记录为准。

`ReaderEbookIntegrationTest` 与 `BookshelfRenameTest` 使用 Android 真实窗口／WebView 交互。当前环境缺少可用模拟器硬件加速，尚未取得本轮设备用例实际运行结果；设备测试包构建结果亦以验证记录为准。浏览器与 JVM 测试不能替代手机 WebView、系统选区、仿真动画或实际帧率验证，不宣称真机 120 fps，也不把已有 Windows 工作视为本轮验收通过。

## Windows 待验收

- [ ] 原 EPUB／MOBI HTML、CSS、图片与脚注按章读取，持久资源缓存可热开；不重复走原生分页、不生成全书页表。
- [ ] DOM 选区、简繁、编辑预览、目录／进度跳转、书签／笔记、图片和同步朗读均维持规范 UTF-16 坐标。
- [ ] 字体切换不复用旧字体缓存；CSS／模式切换与 DOM 重建保持当前锚点，错误不写回零位置。
- [ ] 像素图标笔画清晰，浅色／深色／墨水屏和 RTL 状态正确。
- [ ] 长按改名不修改源文件，待下载书改名后下载与云端更新均保留名称和记录。
- [ ] 多本同名上传确认后界面可继续操作，失败和取消不丢失必要反馈。
- [ ] 搜索双击定位可返回原锚点，首次手动移动后清除临时入口。
- [ ] 缺失链接的数字脚注按章补识别，合法数字正文不误判，旧缓存与编辑锚点不移动。
- [ ] 快速滚动减少重复工作，缓存有界；实际帧率通过设备测量，不用刷新率请求替代性能验证。
