# Android EPUB：Readium 3.4.0 迁移说明

> 历史记录：1.11.4 已按作者要求移除 Readium 运行依赖，EPUB／MOBI 统一使用兼容引擎。下文记录此前迁移，不代表当前实现；当前方案见 [兼容引擎说明](legacy-engine-1.11.4.md)。

本次迁移范围为 E阅 Android 的 EPUB 导入与阅读。目标版本固定为 Readium Kotlin Toolkit **3.4.0**，保留现有 Compose 工具栏、设置、书架及 TXT、PDF、MOBI 链路。Windows 工程没有随此次迁移升级工具链或更换引擎。

这是一项增量迁移：旧 EPUB 引擎仍可选择，旧书库不被批量重导入，原有书籍 ID、章节 ID、文字偏移、标注及云端身份不会为了适配 Readium 而直接重建。

## 工具链与构建入口

| 项目 | Android 配置 |
| --- | --- |
| Readium | `readium-shared`、`readium-streamer`、`readium-navigator`，均为 3.4.0 |
| Gradle | 9.7.0 |
| Android Gradle Plugin | 9.3.3 |
| Kotlin／Compose Compiler | 2.4.20 |
| Compose BOM | 2026.06.01，生产与测试保持一致 |
| Lifecycle | runtime／viewmodel-compose 2.11.0，与 Readium 传递依赖保持一致 |
| JDK／字节码 | JDK 17／JVM 17 |
| 编译 SDK | Android 37.0，SDK 包 `platforms;android-37.0` |
| Build Tools | 36.0.0 |
| 系统支持 | `minSdk` 26，`targetSdk` 35 |
| Java API 兼容 | `desugar_jdk_libs` 2.1.5 |

Readium 3.4.0 的 Kotlin 元数据和 AndroidX 依赖不能直接沿用原来的 Kotlin 2.1.21、AGP 8.9.2、compileSdk 35 组合。因此先使用独立依赖探针检查，再调整 Android 工具链和生产模块。Compose、Lifecycle 也须在编译与运行时对齐，避免编译通过后出现方法缺失。构建与测试的最终结果另行记录，不因依赖可以解析就视为迁移完成。

从仓库根目录运行：

```powershell
.\app\scripts\build.ps1 -Tasks @('testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest', 'lintDebug')
```

受维护入口是 `app/scripts/build.ps1` 与 `app/scripts/setup-android.ps1`。工具、SDK 和缓存位于 `app/.tools/`，脚本验证下载摘要并仅设置当前进程环境变量。首次构建需要联网及同意 Android SDK 许可。设备测试还需要已授权的设备，APK 编译成功不等于已经通过真机测试。

## 模块职责

| 模块 | 职责 |
| --- | --- |
| `DocumentImporter`／`ReadiumEpubImporter` | 完整保存源 EPUB，调用 Readium 解析元数据、阅读顺序及目录，生成兼容文字索引和导入诊断。 |
| `ReadiumPublicationRepository` | `AssetRetriever`、`PublicationOpener` 与 `DefaultPublicationParser` 入口，管理容器资源及只读字体附加资源。 |
| `ReadiumEpubSurface` | 将成熟 `EpubNavigatorFragment` 嵌入 Compose；版面、图片、CSS、多栏和翻页由 Readium 处理。 |
| `ReadiumContentsPanel` | 完整展示 `publication.tableOfContents`，保留多级结构及每个锚点；独立进入现有正文章节／AI 语音管理。 |
| `ReadiumLocatorBridge`／`ReadiumLocatorIndex` | 原 UTF-16 字符偏移、旧章节与标准 Locator 的双向映射，区分精确结果、估计、多义和失败。 |
| `ReadiumLocatorStore` | 以附加 AtomicFile 保存 Readium 位置及标注 Locator，附带内容版本，不替代旧书库结构。 |
| `ReadiumEpubCompatibility` | 根据用户引擎选择与暂不支持的偏好决定兼容路径，并向设置界面提供原因。 |
| `MainActivity` | 使用 `FragmentActivity`；恢复前安装 Readium dummy factory，恢复后在 `onResume` 前移除 dummy，重新从原文件与 Locator 打开。 |

依赖的是 Readium **Streamer 模块**提供的 `PublicationOpener`，不是继续以自研 ZIP／OPF 解析器作为新 EPUB 的包解析入口。现有 `HtmlChapterParser` 仍用于产生兼容文字投影和偏移索引；该投影不作为 Readium 的页面内容，也不参与 Readium 分页。

## 原文件、资源与诊断

新导入的 EPUB 将完整复制到应用私有目录 `files/books/<bookId>/source.epub`。CSS、XML、图片、内嵌字体及其他资源仍保存在原归档中，由 Readium 容器按需读取；不会只保留提取的正文来代替原书。此策略会占用一份原 EPUB 的存储空间，目的是避免临时读取权限或外部原文件变化使书库失效。

已有书籍仍支持原 `sourcePath`／已授权的 `sourceUri`，不在启动时强制复制或覆盖全部旧源文件。读取授权或原文件缺失时应保留明确错误，不能以空章节或 0% 位置覆盖原记录。外部源 EPUB 不因本次迁移而改写。

导入后保存 `readium-publication.json`，记录版本、manifest 和 spine 对应关系。正文、图片引用及字数等兼容索引是辅助数据。空白封面页、纯 SVG 等阅读顺序项仍保留自己的位置，不能为了减少空正文就重编号后续章节。

导入诊断位于 `files/import-diagnostics/<bookId>.json`，记录 Toolkit 版本、文件名／字节数、执行阶段、Readium 警告和失败原因。诊断文件不放在待回滚的书籍目录中，失败清理未完成的导入时仍可查看。受 DRM 限制、缺少正文资源、损坏容器及无法读取等情况必须清楚报错。

应用字体通过只读附加资源提供给 Publication，再使用 Readium 字体声明配置；不会把字体写进源 EPUB，也不使用巨大的字体 data URI。书内自带字体由原容器提供，应用内置字体的本地副本采用原子写入缓存。

## 阅读、目录与分页

默认 EPUB 阅读路径为 `EpubNavigatorFragment` + Readium CSS。外层 Compose 负责现有工具栏、进度、笔记气泡及设置；页面不再由 E阅的 Compose 文本分页器重复生成。页码来自 Navigator 的当前资源分页回调，不建立一套全书 Compose 页表。

1.11.2 修正 Navigator 生命周期接入：Fragment 实际进入 STARTED 后才安装点击／批注监听和位置观察，STOP 或释放时移除并取消观察；偏好、装饰、选区及异步定位也检查当前生命周期。不能在仅调用 FragmentFactory 创建、尚未挂载时注册依赖 ViewModel 的批注监听。Readium 在 CREATED 阶段也可能完成页面加载，因此后台回调仍记录 loaded／当前页／页数，只有控制命令和位置上报等待 STARTED；不能把后台完成事件直接丢弃。

MOBI 和兼容 EPUB 仍使用独立的本地 WebView。1.11.2 将其初始化参数放入受 CSP nonce 保护的主文档脚本，JSON 中的 HTML 结束标签与 Unicode 行分隔符需转义；`onPageFinished` 只保留幂等兜底。初始化在首次上报页面前恢复原位置，然后主动上报 viewport；空白页和纯图片封面不能依赖文字可见性观察器来结束加载。此修复不重建书籍或改变跨端记录协议。

1.11.3 的兼容阅读缓存改为按需生成 MOBI 章节 HTML，不在首次打开时预先封装所有章节；此处仅升级可重建的本机 MOBI 展示缓存，不改变原文件、书籍 ID 或跨端记录。切章复用同一 WebView，旧页面回调仍须经过 generation 检查，主文档不能进入 HTTP 缓存。分页的最后一个物理视口需补足长度，以免浏览器将末页滚动位置截断，导致左右边距偏移。

Readium 可见位置引文的上下文取自当前文本节点，不能使用包含隐藏注释、样式和脚本的整段 body 文本。该调整没有放宽旧批注的精确匹配或歧义拒绝规则。字体等非正文资源失败仍由浏览器使用可用字体回退，同一资源每次打开只提示一次，并保留底层错误原因；不能把降级显示当成原字体已经修复。

目录直接取原书 `publication.tableOfContents`，保留层级、重复 href 与不同片段锚点，不能以 spine 文件列表代替原书完整目录。目录全屏显示，可展开／收起；原书没有目录时明确说明，并提供“正文章节”入口。

目录的“章节语音管理”继续使用现有批量 AI 生成、缓存徽标、进度与中止控制。TOC 小节与正文资源并非总是一一对应；同一正文资源内的多个目录小节可能共用一个语音缓存章节。无法唯一对应旧章节的 TOC 项不会随意挂上某一章的缓存状态。

## 旧位置、书签、批注与 TTS

旧记录仍以原章节索引、UTF-16 字符偏移和摘录为兼容坐标，不能将 Unicode 码点数量、浏览器文本节点偏移和 UTF-16 偏移混用。

映射优先使用已有 spine 索引；旧书首次打开时以通用的原文比对建立资源／章节关系，之后复用持久索引。首次建立旧书映射可能需要额外时间，但不会为此重导入旧书或重建版面页表。

旧坐标转 Locator 时生成 href、文本前后文、highlight 及进度，并附带有摘要保护的兼容提示。Readium 回传位置时核对内容版本、文字摘要、引文及进度，避免把旧兼容提示错误用于新的选区。无法唯一匹配时返回 `Ambiguous`／`Unresolved`，不将其伪装成精确批注。

阅读位置可以在没有可见文字时使用资源进度作为估计；批注、划线与朗读高亮需要精确文本映射。单独的 Locator 附加文件可失效或重建，旧书架与原标注仍保留。正文编辑引起内容版本变化后，不能继续套用旧版本的附加位置。

交互 EPUB 若在 `body` 中嵌入 `script`／`style`，浏览器 Range 引文与已移除这些节点的兼容文字投影可能不一致。这些节点附近的精确位置仍可能映射失败；此时显示诊断并保留原记录。此边界尚未完成设备渲染验证。

TTS 继续使用既有系统／AI 朗读会话与缓存规则。Readium 提供定位与装饰，朗读高亮用官方 Decoration API；页面可见文字只做只读位置查询，不修改原 DOM 或自行分页。跨越一个资源内多个旧章节的视口应分别映射首尾锚点，不能把跨章文本块强塞进单一旧章节。

## 保留旧引擎与已知边界

EPUB 设置中可显式选择 **Readium／兼容旧引擎**，默认 Readium。暂不支持的偏好走兼容路径，保留原选择和其他设置，不把用户配置静默重置为默认。

目前由兼容路径保留的功能包括：上下翻页、覆盖／仿真翻页效果、简繁显示转换、自定义图片背景、未保存正文修改的预览，以及已有图片笔记／标记。Readium 路径尚未接入新建图片长按批注；图片能正确显示不代表图片批注已经兼容。保留路径不等于保证任意复杂固定版式或纯 SVG 都获得与 Readium 相同的显示效果；验证报告需分别说明实际支持范围。

Readium CSS 自身会按 CJK、RTL、固定版式及作者样式限制部分字体／间距偏好。开启“使用原书 CSS”时，部分软件间距和对齐设置可能不覆盖作者样式；CJK／RTL 下也不能宣称所有字距、词距、对齐选项均有效。需要原有行为时可选择兼容旧引擎。

上游 3.4.0 的 `Manifest.conformsTo(EPUB)` 要求 spine 全部为 HTML／XHTML；如果 spine 本身包含 SVG，`WebViewServer` 可能不再为 XHTML 注入 Readium CSS／脚本，原生翻页、定位及批注尚不能视为兼容。该类文件仍允许完整导入；遇到原生功能异常可选择兼容旧引擎。公开 SVG 样本的导入验证不代表此类原生渲染已经通过验收。

**上游重复 spine 定位限制：**Readium 3.4.0 的 `EpubNavigatorFragment.go()` 对资源 href 使用第一次匹配。当 readingOrder 多次引用同一个 href 时，标准 Locator 导航不能保证定位到后一次出现。此处不能通过删除重复正文、改书名或为某本书写规则来掩盖；导入必须保留原阅读顺序，无法唯一恢复的位置需诊断并使用兼容路径。该限制与 TOC 中多个不同片段指向同一资源不是同一问题。

当前不提供 LCP／其他 DRM 的解锁能力。受保护文件应明确拒绝，不能跳过失败资源后显示不完整书籍。TXT、PDF 仍保留原读取与渲染入口，MOBI 未在本轮迁移为 Readium EPUB。

## 验证范围与结果

共享的标准测试构造器位于 `app/app/src/testShared/java/com/eyue/reader/data/readium/ReadiumEpubFixtures.kt`，覆盖 EPUB 2 NCX、EPUB 3 嵌套导航、图文／CSS／字体、UTF-16 XHTML、固定版式及缺失资源等组合。验证不能只依赖某一本私人书籍。

应分别核对：依赖与编译、导入原件字节不变、资源可读、旧库不被重编号、目录完整、Locator 迁移、选择与装饰、朗读／暂停跟随、旋转／进程恢复及真机图文分页。针对损坏文件还应核对诊断、回滚与不覆盖旧记录。

本轮完整本地构建、测试包、lint 与两个性能测试模块编译通过，1187 项 JVM／Robolectric 测试为 0 失败、10 项私人样本可选回归跳过；9 本公开 EPUB 样本的导入与索引验证通过。最终安装包与检查详情见 [验证记录](verification.md)。

按作者要求，修正字体资源钩子重复执行后未继续真机测试，也未上传新版本。原生 Readium 图文版面、翻页手感、装饰和实际音频仍待设备验收；公开样本导入成功不能代替这部分结论。1.10.3 及更早版本的记录属于历史结果，不用于证明此次迁移已通过真机测试。

## 上游与许可

- [Readium Kotlin Toolkit 3.4.0](https://github.com/readium/kotlin-toolkit/tree/3.4.0)：BSD-3-Clause。
- [Readium CSS](https://github.com/readium/readium-css)：BSD-3-Clause；内含字体按各自许可分发。
- 项目和软件内的许可说明见 [开源相关](open-source.md)；原许可证同时保留在 Android `assets/legal/licenses/`，可离线查看。
