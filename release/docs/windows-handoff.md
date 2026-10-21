# E阅 Windows 端开发交接

最近更新：2026-10-21。当前基线：Android **1.10.0 / versionCode 39**。最初功能清单来自 1.9.8，接手前必须先阅读 [1.9.9～1.10.0 增量交接](windows-handoff-1.10.0.md)，其中列出数据访问、缓存、朗读与云端界面的变化及验收要求。

本文供新对话独立接手。项目分为 `app/`、`windows/`、`release/` 三个目录；本地 `windows/` 已有 Kotlin／Compose Desktop 开发内容及未提交工作，不能覆盖或当作已完成验收。下文明确区分现有行为、兼容要求和建议路线。除特别说明外，路径均相对仓库根目录。

## 1. 从哪里开始

- 本地项目：`D:/Project/E阅`。
- GitHub：[byonesheart/ERead](https://github.com/byonesheart/ERead)，公开发布分支为 `main`。本地工作分支应以 `git status` 为准，不要为阅读本文切换或重置已有 Windows 工作。
- Android 1.10.0 源码及发布标签：`v1.10.0`，功能提交 `7e8504d3da73d10223702a6c7e32fb755bfa74e3`。
- 1.10.0 已生效更新清单提交：`59ced0a9ca8059cd143b7081742c05151713f4be`。后续文档提交不改变该软件基线。
- 已发布安装包：[1.10.0 Release](https://github.com/byonesheart/ERead/releases/tag/v1.10.0)。这是 Android APK，不是 Windows 安装包。
- Android Gradle 工程根目录为 `app/`，其中 `:app` 应用模块位于 `app/app/`。Kotlin 2.1.21、Java 17、AGP 8.9.2、Jetpack Compose，最低 Android API 26、目标／编译 API 35。
- Windows 工程统一放入 `windows/`；两端的安装包、说明、更新清单和共用图片放入 `release/`。

```text
E阅/
  app/                 # Android Gradle 工程根；应用模块为 app/app/
    app/src/           # Android 源码、资源和测试
    scripts/           # Android 构建与工具脚本
    .tools/            # 本机开发工具、日志、隔离测试资料（不提交）
  windows/             # Windows 端开发目录
  release/
    android/           # Android 发布 APK 与源码包
    windows/           # Windows 发布产物
    docs/              # 共用说明、法律页面副本及历史记录
    assets/            # 共用图标、作者头像、赞赏码
    marketing/         # 宣传原始素材
    README.md          # 下载与软件介绍
    update.json        # Android 更新清单的维护位置
  README.md            # 仓库导航
  LICENSE
  update.json          # 现有 Android 客户端所需的兼容副本
```

先阅读 [仓库导航](../../README.md)、[下载与软件介绍](../README.md)、[最近验证记录](verification.md)，再沿本文的源码地图核查实现。[软件说明](../E阅软件说明.md) 标注的是 **1.9.2**，包含旧的主题保存、TXT 保存、全书计页和批量入口描述，不能作为当前实现的唯一依据。[CHANGELOG](../CHANGELOG.md) 及历史分析资料也应按版本阅读。

## 2. 用户目标与开发边界

Windows 端应延续 E阅的定位：本地优先的小说、漫画阅读器，集阅读、听书、标注和个人 WebDAV 备份于一体。桌面目录为 `windows/`。当前本地 [Windows README](../../windows/README.md) 记录 Kotlin 2.1.21／Compose Desktop 1.8.2／JDK 17、Windows 10/11 x64、开发版便携包及首版覆盖全部主要功能的方案；接手时应核对 Windows 开发对话中的既有决定，不依据本文早期内容重复选型。

最初交接时列出的决策如下；已有结论的继续沿用，只补充尚未确定的部分：

1. 支持哪些 Windows 版本、是否只做 x64；提供安装版、便携版还是两者。
2. 首版是否要求下述全部功能，还是先完成核心阅读与 Android 备份互通。
3. 桌面界面希望沿用多少手机端风格，以及可共享业务代码的边界；工程保留在 `windows/`。
4. 是否要求读取 Android 已有 WebDAV 备份；建议将协议兼容作为目标，但当前尚无 Windows 实现验证。

不要把“Windows 移植”理解为把 Android 工程换一个构建目标。沿既有桌面方案评估可共享的 Kotlin 业务代码与平台依赖，分别验证桌面 UI／文本渲染和系统适配。本次补充交接没有修改、编译或发布 Windows 应用。

### 工作方式

- 小范围改动，先定义验收目标，不顺带重构 Android 阅读器。
- 新对话开始先检查 Git 状态，保留现有未提交工作；在 `windows/` 和明确的分支开展工作，新增分支默认前缀 `codex/`。
- 保持 `app/` 中的 Android 工程可以独立构建、发布；不要改写 Android 的存储格式。
- 遇到已有授权内的常规实现选择可以继续工作；技术栈、产品范围等尚未确定的重大选择应明确说明。
- 不把私人书籍、报错文档、真实语音密钥、网盘密码、签名私钥、`app/.tools/` 内容提交仓库。

## 3. 现有产品行为：桌面移植的功能基准

### 3.1 导入、分类与书架

- 支持 TXT、EPUB、未加密 MOBI、PDF、图片和漫画文件夹。通过一个“＋”导入文件，另保留“导入漫画文件夹”。
- EPUB／MOBI／独立 PDF 按是否含正文自动归为小说或漫画；格式与小说／漫画模式是两件事。漫画文件夹内的 PDF 按漫画处理。
- TXT 支持 UTF-8、UTF-16 和 GB18030 等现有解码路径。MOBI 支持 PalmDOC、HUFF/CDIC；混合 MOBI/KF8 使用兼容 MOBI 正文。纯 KF8／AZW3、DRM 不是当前能力。
- 作者只能提取文件明确提供的信息，没有则留空。“最新章节”是已导入内容的最后一章，不代表在线追更。
- 漫画封面取阅读顺序的第一页；章节／小节名取文件夹或 PDF 名。多个可读分支形成章节与嵌套小节；任意层只有一个可读子文件夹或 PDF 时递归合并到父级。根目录直接图片与子目录混合时，以 `ComicHierarchyPlanner` 的实际顺序为准。
- 导入立即显示待加载书卡和封面环形进度；没有阻塞式“整理文件”弹窗。多文件每完成一本就出现一本，导入期间可继续添加任务，也可操作其他书的云端功能。
- 优先引用原文件，仍允许生成必要的正文、封面、图片与阅读缓存；不能宣传“导入完全不占空间”。桌面端应持久保存所选本地来源，处理源文件移动、删除及失去访问权限。
- 导入也检查重复／同名书籍；无法明确判断时让用户选择保留的副本，不能无提示覆盖。
- 书架支持独立的小说／漫画分组、网格／列表、书名作者搜索、全部／在读／读完、书籍信息、总字数和 AI 缓存占用。
- 书架批量管理与云端还原是两个入口；批量管理提供删除、按本地／云占位状态识别上传或下载。删除确认列明书籍和各自占用，可选择连带清理云端。
- 云端未下载封面轻度虚化；导入、上传、下载成功不弹提示，失败才报错。删除书架条目不删除外部源文件。

### 3.2 页面、设置与阅读

- 主功能仍为阅读历史、书架、设置；外部顶部顺序为小说／漫画、夜间、书签与笔记、导入。已经移除大标题栏、日期问候和多余标语。
- 单击正文只切换阅读工具栏，不直接弹出设置。上栏含返回、书名、快速书签、可用时编辑、夜间、搜索等操作。
- 下栏包含目录、朗读、书签、界面、设置及进度条、上一章／下一章；已移除独立“笔记”按钮。笔记仍能从书签入口查看。
- 进度条跳转后提供返回先前位置的按钮，显示原章节和进度；关闭工具栏后不保留该返回按钮。
- 模式为滚动、左右翻页、上下翻页；只有左右翻页提供覆盖／滑动／仿真三种效果。上下模式不额外展示翻页效果选择。
- 目录全屏，默认开启可折叠二级目录。原书有效目录优先；TXT 可保存多条命名正则，但只启用一条。规则列表不直接展开详细表达式，点击或新增后编辑。注释不能误识别为标题。
- 全文搜索与替换：结果显示章／节位置；单击不离开搜索页，双击定位原文；定位后首次手动移动清除关键词高亮。
- 字体入口：系统、宋体、黑体、加粗、更多；更多支持其他字体和导入。背景可用色块或导入图片。
- 界面与阅读设置位于工具栏同一层级。界面使用紧凑、需拖动才改变的滑条，并配加减微调；设置直接显示原更多设置内容。
- 主题可新建，始终保持一个配置选中；改动自动保存至所选配置。缩略图取背景，图上直接显示配置名；长按进入删除状态，小 × 删除需确认。
- 小说／漫画各自保存阅读设置和主题，并支持恢复默认。恢复默认保留导入字体和已保存目录规则。
- 深色模式全局生效，所有工具栏／搜索／跳章图标均需适配；外部设置直接展示浅色／深色／系统三选一。
- 简繁转换只改变显示，不能改变原文、标注偏移和保存内容。
- 保留墨水屏模式和宽屏双页能力的产品目标；Windows 应按窗口宽度、DPI 适配，不照搬手机固定尺寸或 Android 电量／音量键逻辑。

### 3.3 1.9.8 的计页与性能约束：不要退回旧方案

1. 页脚为“百分比 · **本章 当前页 / 本章总页数**”，不汇总全书页数。
2. 导入只准备续读章；阅读优先准备当前章，再预取前后各一章；滚动页脚只计算当前章。
3. 不为显示页码扫描、准备所有章节，也不在热开时恢复全书页表。`ReaderBookPageIndex.kt` 仍存在用于历史缓存相关代码，不代表阅读时应该重新接入它。
4. 当前章分页结果持久缓存；正文、样式、字体、DPI／平台、视口、简繁、间距和四边边距参与失效。夜间和背景等不影响文字边界的改变不应无故重排。
5. 全书百分比与拖动定位使用正文字符／插图位置；翻页页码不是稳定存储坐标。末章末页／末双页能标为读完，正文偏移仍保存原页锚点。
6. 首次排版和改变排版仍有当前章的必要计算。切模式时保持旧页可读，后台准备目标当前章，准备好后按最新原文位置切换。取消的旧任务不能修改新模式的位置或清除新任务的恢复状态。
7. 当前章正文、样式、页表、目录、图片与脚注必须使用同代快照，避免 CSS 切换时旧闭包访问空文档、错范围或闪退。
8. 手势达到阈值即翻一次，不等抬手；同次手势不能因页面状态变化重启而翻两次。Android 覆盖／滑动动画 180 ms、仿真 300 ms，仅作交互参考，不要求桌面逐毫秒照搬。
9. 上下左右边距独立：0–64 dp；小说默认左右 24、上下 18，漫画默认 0。测量与绘制使用同一组物理边距，随主题保存；桌面需定义与 DPI 对应的逻辑单位。

EPUB／MOBI 默认普通正文使用软件排版，标题、引用等特殊内容保留相对格式，字体替换为软件选择。可选“使用原书 CSS”默认关闭；开启后采用可识别的原书文字样式。当前 Android 是原生文本渲染，支持范围不是完整浏览器 CSS，不能将其描述为原样支持所有 CSS。Windows 若更换渲染器，需要重新验证选区、脚注、搜索、朗读锚点和长章性能。

### 3.4 笔记、标记与编辑

- 书签、笔记、彩色标记、直线／波浪划线都能查找、定位和删除；书内外过滤项均包含划线。
- 选中文字后的菜单自适应尺寸、紧凑排列，超出部分收进竖三点菜单；选项生效后关闭原菜单，避免遮挡后续界面。
- 添加笔记／标记／划线在选中文字上方显示不透明气泡，空间不足放下方；查看短笔记、标记和划线也使用气泡并显示日期，长笔记才用独立弹窗。图片可作相应标注。
- 原书 EPUB／MOBI 注释点击小标直接显示；原书脚注与用户笔记是不同数据，清用户笔记不能清原书注释。
- TXT／EPUB／MOBI 小说支持选择文本编辑、当前章编辑、单项替换和批量替换。
- **编辑先只显示私有预览，手动保存才正式提交并写回源文件。** 顶部显示保存按钮和保存进度；正常退出／关闭需处理未保存内容。系统强杀不等于正常退出，不能承诺能显示确认。
- 当前写回在开始保存后备份源文件，并在提交前检查源文件自写回准备后是否变化；安全暂存、结果校验和失败回滚保留。**TXT 及已经编辑过的 EPUB／MOBI 尚不能保证识别整个预览期间的外部修改**，Windows 应记录开始编辑时的源身份并在保存前核对。正式云备份不读取未保存草稿；按书管理忙碌状态，失败和取消都必须释放，不能卡住其他书编辑和云操作。
- TXT 当前正式保存输出规范 UTF-8，原编码／换行不保证逐字节保留。EPUB／MOBI 重建时保留资源，并重新解析验证。PDF／图片不支持此正文写回。

## 4. 朗读、AI 与角色音色

### 播放交互

- 系统 TTS 与 AI 使用同一入口切换，显示“当前使用：”；配置支持命名保存，测试连接有明显的成功／失败结果。
- 朗读按钮为耳机图标，开启变红；再次点击直接中断。悬浮条随阅读工具栏出现，提供前后段、暂停／继续、速度、同步、停止和快捷引擎配置。
- 默认系统语音；速度默认 1×、范围 0.5–2×；页面同步、AI 失败系统替代、角色提示均默认关闭。
- 朗读高亮跟随实际语音位置；开启同步后页面自动滚动／翻页。手动移动停止 1 秒后重新判断；原朗读节点仍在当前页时从该句开头继续，否则从当前页朗读。
- 未同步时，移动页面可提示“从当前页面朗读？”，确认切换；返回原位置只跳回原朗读位置。主动暂停不能因手动移动而自动开始播放。
- 记笔记不中断朗读；正在播放时切换引擎重新开始对应位置的朗读。应用后台再回前台不能反复重播切后台时的旧段落。
- 支持系统媒体播放栏、耳机播放／暂停；普通通知不应暂停，音乐／视频等实际音频竞争才处理。Windows 必须重写媒体会话和音频生命周期，不能照搬 Android Service。

### 协议与请求

| 协议 | 当前请求与认证 | 当前响应 |
| --- | --- | --- |
| OpenAI 兼容 | `POST /audio/speech`，`Authorization: Bearer …`；`model/voice/input/response_format=mp3`、可选 `instructions` | 原始音频 |
| MiMo | `POST /chat/completions`，`api-key`；风格为 user 消息、正文为 assistant 消息；`stream=false`、WAV 音频 | `choices[0].message.audio.data` 的 Base64 WAV |

地址规范化和配置校验以 `AiSpeechConfigStore.kt` 为准，不能把两个协议都改成 Bearer。`mimo-v2.5-tts-voicedesign` 需要音色描述，不发送预置 `audio.voice`；声音描述与服务的 voice ID 不是同一概念。

各种普通合成失败均重试：首次失败后最多追加 **3 次**，共最多 4 次尝试，取消立即终止；不是只重试无音频。开启系统替代后，最终合成失败的片段用系统 TTS 读完再回 AI；播放器错误是另一处理路径，不应误写成全部错误都能降级。连接测试共用实际请求／解码路径，会实际合成短文本。日志和错误必须脱敏。

### 预取、缓存与批量转换

- 按正文段落／句子切有限片段，通常每片不超过 240 个 UTF-16 单元，保护代理对；采用正文边界而不是屏幕页边界。即时播放最多预取两个后续片段，并能衔接下一章。
- AI 无逐字时间戳，当前按播放时长近似推算高亮位置，不能承诺逐字精准。速度由本地播放器改变，不重新生成音频。
- 音频按书独立保存、统计、清理；漫画不提供清理 AI 配音缓存的入口。**音频缓存只在本机，不随 WebDAV 书籍备份上传。**
- 片段键由协议、最终 endpoint、实际请求 JSON 构成；密钥、配置名称／ID、播放速度不进片段键。修改配置不能删除旧缓存或把旧音色误当当前音色已完成。
- 章节完成记录另校验正文版本、源缓存属性指纹（章节 ID、路径、大小和修改时间）、配置身份、角色摘要、上章对白 seed、全部片段及扩展名；不为刷新徽标读取正文计算哈希。整章音频均存在才完成。即时朗读完整章也应形成完成标识。
- 批量转换选整书或目录中的章节，最小管理单位是章节；已完整缓存章跳过，**不完整章重新合成全部片段，原子覆盖已有部分**，不是只补缺片。
- 同书多章并行，当前最多 3 章、全局批量 HTTP 请求上限 3；用户能从目录或系统通知中取消。进度在通知和对应章节显示，不用阻塞窗口；完成显示带 √ 耳机。
- 任务需持久保存计划、身份、进度和取消状态，运行时核对正文／配置／角色是否变化；不能只靠阅读窗口里的临时协程。清理缓存后旧请求不能把音频重新写回来。

### 角色音色

- 已取消并移除“AI 音色提取／整书分析角色”功能，移植时不要根据旧聊天重新加回。
- 现为每书独立角色与通用角色，预设男／女旁白，可启停，类别为角色／旁白。
- 名称、名称正则、音色、描述、场景五项主要文本合计最多 3000 Unicode 码点；名字会加入已转义的正则分支，重复名字需提示。可从选中文字新增角色、别名、描述或场景，并编辑后保存。
- 每次请求最多加入一个角色。使用本地正则、对话引号及说话归属启发式，不是大模型角色识别；歧义时按句拆分，未知对白沿用上一对白角色。
- 优先级：本书角色 → 本书旁白 → 通用角色 → 通用旁白。旁白的“随机”以稳定散列实现，相同位置重复请求保持一致；跨章对白 seed 先顺序建立，再并行生成。
- 角色的“音色”是追加到提示词的自然语言描述，不替换服务的预置 voice ID。功能在 AI 配置中可选，默认关闭，并明确提示尚不稳定。

## 5. 数据、坐标与 WebDAV 兼容

### 本地模型

- `LibraryRepository` 当前写 `library.json`，`version=13`，UTF-8／JSON，原子写入和锁保护；不能因为有 version 字段就假定已有统一版本迁移框架。
- 自 1.9.9 起，最近进度和阅读时长另存 `reading_positions.json`。`positionRevision` 匹配后合并根级阅读时长，各书位置再校验 `id` 与 `contentVersion`。只复制 `library.json` 会遗漏最新进度；缺失、过期及损坏位置文件的处理见 [增量交接](windows-handoff-1.10.0.md)。云端仍从合成状态写入既有 manifest，不直接上传这个本地位置文件。
- 枚举按名称保存；Book／Chapter／Annotation／ShelfGroup 使用字符串 ID。保留现有 ID、`contentVersion`、来源身份、角色与云端状态，避免标注和音频关联丢失。
- 阅读与标注偏移按 Kotlin 字符串的 **UTF-16 code unit** 语义，不能换成 UTF-8 字节或 Unicode 码点；补充字符、emoji、简繁显示映射均要测试。
- 用章节＋字符偏移＋必要的插图位置恢复正文；`pageIndex` 是排版相关提示，Windows 字体和窗口改变后需重定位。
- 漫画小节区间是所属章节平铺页列表的 `[startPage,endPage)`，`PageSource.pdfPage=-1` 为普通图片。
- Android 的 `content://`、私有路径、Keystore 密文不可直接当 Windows 路径／凭据使用。Windows 应重建资源路径并要求重新配置密钥；分页二进制缓存也必须按 Windows 渲染环境重新生成。

### 重复规则

用户明确要求：**原文件名完全一致＋原文件字节数一致，视为同一本书**；不把笔记等缓存算入大小，文件名当前区分大小写。这是产品规则，并非强内容校验，同名同大小的不同文件也可能被判同书。

其他云端判断还会用规范正文／页面 identity 或原文件 SHA-256。相同可合并，明确不同保留副本；同名且无法确定必须弹窗选择保留一本或多本，上传和还原都执行。导入同名候选还包含同模式标题忽略大小写匹配。不能只按展示书名就静默覆盖。

### 云端布局与协议

```text
<规范化的 WebDAV 服务器地址>/ERead/
  catalog.json                           # version=1，bookIds
  books/<bookId>/
    manifest.json                        # version=1，最新书籍信息及归档清单
    backup-<revision UUID>-0000.bin       # ZIP 的连续字节分块
    backup-<revision UUID>-0001.bin
    backup-<revision UUID>-cover.jpg
```

- ZIP 内为 `metadata.json` 和 `assets/<序号>.<扩展名>`；资源有长度和 SHA-256，归档也有总长度和 SHA-256。路径、资源清单、长度、摘要都要校验，不能直接信任归档路径。
- 新上传块为 **1 MiB**；旧续传任务有 4 MiB 兼容路径。下载按 manifest 声明拼接，不假设所有历史块恰为 1 MiB。
- 当前约束包括 metadata 16 MiB、归档／展开资源总量 32 GiB、最多 32768 块；具体边界和安全路径以源码为准。
- **最新记录以 manifest 为准。** 正文／资源未变时只更新 manifest，ZIP 中 metadata 可以保留旧进度；下载用 ZIP metadata 校验资源和内容后，最终从 manifest 还原阅读记录，不能用旧 ZIP 记录覆盖新进度。
- 先完成块和封面，再提交 manifest、catalog；只记录服务器已确认的续传块。catalog 使用 ETag 条件写与有限重试。
- 当前同书 manifest 普通上传为直接 PUT，没有双设备同书自动合并机制。这是按书备份与手动还原，**不是双向实时同步**；Windows 不得将其宣传成已经支持并发自动合并。
- 手动还原先恢复全部信息为 `cloudOnly` 占位书，再按需下载；已有本地已下载同 ID 书不被信息恢复直接覆盖。
- 自动备份默认关闭，只处理已启用上传的书；计费网络可选，默认不允许。多本并行操作／逐本取消，导入、上传和信息还原不能用一个全局忙碌锁互相阻塞。
- 备份包括书籍资源、记录、标注、所属模式设置、分组和每书角色；**不包括 AI 音频、WebDAV 密码、AI 密钥**。云恢复的来源转为本机私有副本，Android 原 URI 不迁移。

## 6. 源码地图与复用边界

下列文件均在 `app/app/src/main/java/com/eyue/reader/` 下，可按文件名查找；同一单元格部分后续文件省略相同目录前缀。同为 Kotlin 不代表文件可原样在桌面编译；不少数据类辅助代码仍依赖 Android Context、AtomicFile 或 org.json 的运行环境。

| 领域 | 关键文件 | 移植重点 |
| --- | --- | --- |
| 状态与调度 | `ReaderViewModel.kt`、`ui/EReadApp.kt`、`MainActivity.kt` | 参考任务和状态流，拆开桌面窗口生命周期，不整段复制 Android 入口 |
| 模型／持久化 | `data/Models.kt`、`LibraryRepository.kt`、`ReaderThemeProfiles.kt`、`ReaderSettingsDefaults.kt` | 数据语义、序列化默认、两种模式、主题迁移 |
| 导入与文件 | `DocumentImporter.kt`、`DocumentSource.kt`、`ImportBookConflict.kt` | 文件来源、引用／复制、进度、同名与分类；重写文件选择和权限 |
| 格式与章节 | `MobiArchive.kt`、`MobiToc.kt`、`EpubArchive.kt`、`HtmlChapterParser.kt`、`HtmlTextNormalization.kt`、`TextParser.kt`、`ComicHierarchyPlanner.kt` | 格式解压、目录、字节到 UTF-16 映射、归一化、层次规则 |
| 源样式／注释 | `SourceTextStyle.kt`、`SourceFootnote.kt`、`SourceHtmlFootnotes.kt`、`LazyChapterContent.kt` | 保持内容与样式／脚注边界一致，按章延迟准备 |
| 阅读／分页 | `ui/ReaderScreen.kt`、`ReaderChapterStore.kt`、`ReaderPagination.kt`、`ReaderLayoutPreparation.kt`、`ReaderPageCache.kt`、`ReaderPageProgress.kt`、`ReaderScrollIndex.kt` | 保留锚点与缓存设计，替换平台文本测量；不要恢复全书计页 |
| 阅读 UI | `ReaderPageTurn.kt`、`ReaderPageDragGestures.kt`、`ReaderSelectableText.kt`、`ReaderAnnotationBubble.kt`、`ReaderContents.kt`、`ReaderPanels.kt` | 鼠标、选区、滚轮、布局、动画和目录虚拟化 |
| 编辑 | `data/TxtEdit.kt`、`EbookTextEdit.kt`、`EbookSourceWriteback.kt`、`MobiSourceWriter.kt`、`SourceHtmlEdit.kt`、`AnnotationRelocation.kt` | 私有预览、手动保存、原子替换、回滚与坐标重定位 |
| 语音与角色 | `data/AiSpeechConfigStore.kt`、`AiSpeechClient.kt`、`AiSpeechResponse.kt`、`AiSpeechRolePlanner.kt`、`BookVoiceRole.kt` | 协议、切片和角色可参考；凭据与播放器需适配 |
| 音频任务／缓存 | `AiSpeechCache.kt`、`AiSpeechBatch.kt`、`AiSpeechAutomation.kt`、`ui/AiReaderSpeech.kt` | 缓存身份、整章完成、并发／取消和持久任务 |
| 系统媒体 | `ui/ReaderSpeech.kt`、`ReaderAudioService.kt`、`ReaderMediaButtonReceiver.kt`、`ReaderSpeechPosition.kt` | Windows TTS、播放变速、系统媒体控制及位置回调 |
| 云备份 | `data/cloud/CloudModels.kt`、`CloudArchive.kt`、`CloudBackupService.kt`、`WebDavCloudTransport.kt` | 保持归档／manifest、条件写、续传及安全校验 |
| 冲突／并发 | `CloudBookIdentity.kt`（在 `data/cloud/`）、`data/CloudRestoreConflict.kt`、`CloudUploadDecision.kt`、`CloudLibraryMerge.kt`、`CloudOperationCoordinator.kt` | 同名分支、保留选择、计费网络、每书并发 |
| 通用辅助 | `data/ChineseTextConverter.kt`、`LibraryCleanup.kt`、`AppUpdateClient.kt`、`ConsentStore.kt` | 简繁、清理语义、平台独立更新与协议同意记录 |

### 必须替换或适配的平台层

- Activity／Android ViewModel 生命周期、Intent／SAF／ContentResolver 与文件类型关联。
- Android TextToSpeech、MediaPlayer、AudioManager、MediaSession、通知、前台 Service 和 WorkManager。
- Android Keystore、Context 的数据／缓存目录和 AtomicFile；Windows 使用合适的本机凭据保护、用户数据目录和原子文件写入。
- Android PDF 渲染、PDFBox-Android、位图／Exif／字体与原生文本测量；Windows 重新选择对应实现，不能只改 import。
- 触屏选区与拖动需转换为合适的鼠标／键盘行为；窗口缩放、高 DPI、双页和多显示器切换要有独立测试。
- 不把 Android 每次后台／前台切换绑定的重播逻辑搬到 Windows。窗口隐藏、托盘、最小化和真正退出须分别设计。

## 7. 建议的开发顺序与验收

这些是建议分阶段目标，需结合新对话确认的首版范围。

| 阶段 | 交付与验证 |
| --- | --- |
| 0：桌面骨架 | 在 `windows/` 确认技术方案、分支、安装／便携策略、用户数据目录；不破坏 Android 构建 |
| 1：核心阅读 | TXT／EPUB／MOBI、漫画文件夹／PDF、书架；首开与热开、超长章、连续翻页不卡在全书统计 |
| 2：阅读配置 | 滚动／翻页／上下、双页、四边边距、字体、CSS、主题、深色；所有模式切换保留同一原文锚点 |
| 3：标注与保存 | 选区气泡、脚注、搜索双击定位、替换与草稿；文件只在明确保存时改写，失败可恢复 |
| 4：有声阅读 | 系统／AI、协议测试、媒体键、后台播放、页面同步、缓存与后台批量取消；配置变化不误用完成标记 |
| 5：备份互通 | 用隔离的 WebDAV 目录验证 Android→Windows、Windows→Android；保留新进度，处理冲突、取消、断线续传和计费网络 |
| 6：发布 | 桌面安装、文件关联、退出清理、隐私说明、开源清单、独立更新渠道和下载验证 |

关键测试矩阵：中文／emoji 偏移、脚注多次点击、源 CSS 快速切换、边距与 DPI 改变、模式切换准备期间滚动、旧任务取消、文件移动／只读／外部修改、并发导入／编辑／上传、音频失败 4 次尝试后行为、完整章跳过／半章重生成、相同名字不确定冲突、最新 manifest 与旧 ZIP metadata 不一致。

已有回归可参考，但 Robolectric／Android 测试不能当作 Windows 已通过：

- 格式：`MobiArchiveTest`、`MobiTocTest`、`EpubArchiveTest`、`HtmlChapterParserRobolectricTest`、`ComicHierarchyPlannerTest`。
- 阅读：`ReaderScreenBookPageIndexRobolectricTest`（历史类名，当前覆盖按章计页）、`ReaderPageProgressRobolectricTest`、`ReaderChapterPageCacheRobolectricTest`、`ReaderPageDragGesturesRobolectricTest`、`ReaderMargins*`。
- 编辑／注释：`EbookSourceWritebackRobolectricTest`、`AnnotationRelocationTest`、`ReaderSourceFootnoteRobolectricTest`、`ReaderSearchReplaceRobolectricTest`。
- 语音／云端：`AiSpeech*`、`ReaderNarrationInteractionRobolectricTest`、`ReaderAudioServiceRobolectricTest`、`CloudArchiveTest`、`CloudBackupServiceRobolectricTest`、`CloudParallelTransfersRobolectricTest`、`CloudRestoreConflictTest`。

Android 1.10.0 基线为 **1008 项测试、135 个类、0 失败／错误／跳过**；安装包、设备测试包、独立 Macrobenchmark 测试包构建及 lint 通过，lint 0 错误。未连接手机，没有真机性能基线，也没有因此验证 Windows 功能。详细记录在 [verification.md](verification.md)。本次仅更新交接文档，没有重跑软件测试。

如需确认 Android 未被桌面改动破坏，使用现有脚本：

```powershell
Set-Location -LiteralPath 'D:/Project/E阅/app'
& './scripts/build.ps1' -Tasks @('testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest', 'lintDebug')
```

该脚本的仓库路径为 `app/scripts/build.ps1`，是 Android 构建入口，使用 `app/.tools/`、Java 和 Android SDK，并处理 Windows 中文路径；发布产物输出到 `release/android/`。它不是桌面构建脚本。Gradle 运行期间冻结源码／测试／构建输入，避免边改边测产生无效证据。现有真实 MOBI 回归只使用隔离副本；不得写回或发布用户原书。

## 8. 资源、法律页面与发布

- 软件名 E阅，作者 **byonesheart**。仓库 [ERead](https://github.com/byonesheart/ERead)，B站 [ByOnesHeart](https://space.bilibili.com/481766725)，[反馈 Issues](https://github.com/byonesheart/ERead/issues)，QQ群 **538262088**。长群邀请链接在 `ui/InformationPages.kt`，避免手抄改坏。
- 共用 PNG 图标、头像与赞赏码：`release/assets/E阅图标.png`、`author_avatar.png`、`support_wechat.png`；Android 图标：`app/app/src/main/res/drawable/ic_launcher.xml`，应用内头像与赞赏码副本保留在 `app/app/src/main/res/drawable-nodpi/`。
- README 六张宣传图：`release/docs/images/promo/`；宣传原始素材：`release/marketing/promo_20261018/`。它们描述 Android 版，不要未经修改标成 Windows 实机截图。
- 支持作者文字为“如果觉得好用，可以给我点个免费的赞吗？”；赞赏自愿，不影响功能。外部链接打开浏览器。
- 首次进入需主动同意隐私政策和用户协议；不因同意就自动启用云端或 AI。桌面应按实际文件访问、凭据、后台与联网行为更新说明，不能把 Android 专有描述照搬。
- 现有政策与声明：[隐私政策](privacy-policy.md)、[用户协议](user-agreement.md)、[开源相关](open-source.md)、`app/app/src/main/assets/legal/`。项目已有 GPL-3.0 及第三方许可资料；桌面新增／替换依赖后需重新整理实际分发的依赖清单与声明。
- Neat Reader 只做过静态对比，见 [分析记录](neat-reader-analysis.md)；其反编译内容没有并入本项目，不应将它当成可直接复制的桌面源码。

### Windows 更新必须与 Android 分开

当前 Android 更新清单维护在 `release/update.json`，根目录 `update.json` 保留相同内容，兼容已发布客户端访问的 `https://raw.githubusercontent.com/byonesheart/ERead/main/update.json`。`AppUpdateClient.kt` 的下载 URL 校验限定 GitHub 本仓库的 **`.apk`**。不能把这两个 Android 清单改成 Windows EXE／ZIP 下载地址，导致 Android 用户收到错误更新。Windows 需要另行确定平台／架构明确的清单、版本规则和产物名，产物存放于 `release/windows/`。

用户此前授权 Android 更新后自动推送 GitHub，并上传 APK 到百度／夸克的 `/项目/E阅`，百度提取码 `READ`，再更新 `release/README.md`／Release／清单。这个历史流程不能直接当作已配置好的 Windows 发布能力；桌面版本、网盘目录、产物和渠道需要先明确。现有部分发布辅助位于忽略的 `app/.tools/`，不在 GitHub 源码包中，也不可把其中凭据复制出来。

发布仍遵循：验证产物 → 上传并检查附件 → 发布 Release → 最后更新对用户生效的更新清单。交接文档本身不需要触发软件版本升级、打包或自动上传。

## 9. 可直接发给新对话的开场说明

> 请先阅读 `D:/Project/E阅/release/docs/windows-handoff.md` 和 `D:/Project/E阅/release/docs/windows-handoff-1.10.0.md`，以 Android 1.10.0（v1.10.0，功能提交 7e8504d3da73d10223702a6c7e32fb755bfa74e3）为当前功能与数据基线。继续 `D:/Project/E阅/windows/` 的既有开发，先核查 Git 状态并保留未提交工作，沿用 Windows 开发对话中已确定的技术栈和范围。逐项对齐独立进度持久化、当前及相邻章节窗口、懒加载朗读、明确读取错误、布局代次保护、云端还原进度与实际下载目录展示，不能把交接完成当成 Windows 实现已完成。保留按章计页、UTF-16 原文锚点、每书并发与手动保存语义；兼容现有 WebDAV 格式并以 manifest 最新记录为准。保持 Android 与 Windows 更新渠道独立，不提交私人书籍、密钥或 `app/.tools/`。
