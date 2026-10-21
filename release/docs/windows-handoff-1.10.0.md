# Windows 增量交接：Android 1.9.9～1.10.0

更新日期：2026-10-21。先读本文，再读 [完整功能交接](windows-handoff.md)。本文补充 1.9.8 之后的变化，不能据此认定 Windows 已完成移植或验收。

## 基线与当前状态

- 当前 Android 基线：[v1.10.0](https://github.com/byonesheart/ERead/releases/tag/v1.10.0)，versionCode 39，功能提交 `7e8504d3da73d10223702a6c7e32fb755bfa74e3`；已生效更新清单提交 `59ced0a9ca8059cd143b7081742c05151713f4be`。
- 1.9.9 主要改变数据访问和性能；1.10.0 收拢组件职责、修复封面与错误处理，并增加云端下载目录及还原进度显示。对应说明：[1.9.9](performance-1.9.9.md)、[1.10.0](architecture-1.10.0.md)。
- 本地 `windows/` 已有独立的桌面存储、阅读器及部分解析／AI 缓存实现，且存在未提交工作。不能因为 Android 同名文件已修复，就认定桌面副本自动获得修复。先比较实现，再在 Windows 开发对话中逐项落实。
- `windows/docs/acceptance.md` 当前仍记录 1.9.8 基线，Windows 开发者应将本文验收项合并到其既有清单。本次没有修改 Windows 工作文件，也没有进行桌面编译、发布或跨端验收。

## 本地进度格式：最容易遗漏的变化

Android `data/LibraryRepository.kt` 仍写 `library.json` **version 13**，新增 `positionRevision` UUID。高频保存只写 `reading_positions.json`，该文件**没有独立 version 字段**，结构为：

```json
{
  "positionRevision": "与 library.json 相同的 UUID",
  "readingSeconds": 120,
  "books": [
    {
      "id": "书籍 ID",
      "contentVersion": 1,
      "chapterIndex": 2,
      "pageIndex": 3,
      "characterOffset": 480,
      "progress": 0.25,
      "lastReadAt": 1792540800000,
      "positionImagePath": null
    }
  ]
}
```

加载规则：先读主文件；没有位置文件则沿用主文件的旧位置，兼容旧数据。旧主文件缺少 `positionRevision` 时按 `legacy` 处理，旧书库首次只保存进度也可能沿用该值。位置文件的 `positionRevision` 匹配后，叠加根级累计 `readingSeconds`；各书位置再按 `id` 和 `contentVersion` 合并。版本不匹配时忽略旧位置，不恢复已删除书籍。位置 JSON 损坏时明确报错并保留原文件，不静默当作首次阅读。

结构保存会把当前完整状态原子提交到主文件，生成新 UUID，成功后移除旧位置文件。即使崩溃留下旧位置文件，也不能覆盖新结构。`LibrarySaveCoordinator` 统一约 350 ms 去抖、锁内读取最新状态、结构／位置分流、成功后更新保存基线，以及退出时可在原协程取消后完成的 flush；未保存的编辑草稿仍不能混入正式持久化。

Windows 可以采用自身的增量存储，但读取 Android 本地数据时必须遵守以上叠加规则，不能只复制主 JSON。原文锚点仍使用 UTF-16，页号只是布局提示。参考 `data/ReadingPositions.kt`、`data/LibrarySaveCoordinator.kt`，以及 `ReadingPositions*`、`LibrarySaveCoordinatorTest`、`ReaderEditIsolationRobolectricTest`。

## 云端兼容：没有新云协议

- catalog、manifest、ZIP 内 metadata 仍为 **version 1**，归档资源和 1 MiB 新分块规则未改变。最新阅读记录继续以 manifest 为准，不能被旧 ZIP 内的记录覆盖。
- `records-v2` 是本地待更新判断使用的记录指纹前缀，**不是云端 schema 版本**；`reading_positions.json` 也不是新增的云资源。上传使用加载并合并后的正式状态，经既有 manifest 序列化。
- 1.9.9 按书缓存待更新指纹，只更新受影响书籍。可信未变资源可以复用摘要，内容版本和记录版本分别处理；Windows 没有可靠文件变化身份时必须回退完整校验，不能只凭路径、大小或可恢复的 mtime 判断未变。
- 1.10.0 还原进度是临时 UI／任务状态：读取目录时未知总量，逐本实际处理后发布完成数及比例，匹配和保存阶段显示不定进度。结束后清理，过期回调不能让进度重新出现。设置和书架共用该状态，不占用书籍上传槽位，只禁止重复发起还原。
- 设置显示**本平台实际下载目录**。Android 为应用内部 `files/books/<bookId>/cloud-<UUID>/`；Windows 应从自己的环境和下载实现取得路径，不显示 Android 私有路径或硬编码假目录。

主要入口为 `data/CloudDirtyBookIndex.kt`、`data/cloud/CloudResourceHashCache.kt`、`data/cloud/CloudBackupService.kt`、`data/cloud/CloudRestoreProgressState.kt` 和 `ui/SettingsPreferences.kt`。

## 阅读与朗读需要对齐的行为

下列源码路径均相对 `app/app/src/main/java/com/eyue/reader/`。需要移植的是行为和接入点，不能只复制一个未被调用的类。

| 项目 | Windows 需要对齐 | Android 入口／回归 |
| --- | --- | --- |
| 章节内存 | 存储层和 UI 同时只持有当前章及前后相邻章的正文、样式、滚动块、分页结果；远章只留轻量信息。迟到加载不得重新填充旧窗口。搜索和朗读读取远章时不准备其样式或扩大窗口。 | `ui/ReaderChapterStore.kt`、`ReaderScreen.kt`；`ReaderChapterStoreTest`、`ReaderScreenBookPageIndexRobolectricTest` |
| 读取错误 | 严格 UTF-8 针对**导入后规范化的正文／OCR 缓存**，不替代原始 TXT 编码检测或 MOBI／EPUB 解包。空文件合法；正文引用缺失、文件缺失及坏 UTF-8 明确报章节错误。有 OCR 引用但失效要报章页，无 OCR 引用才可合法为空。取消原样传播，搜索读取失败不能显示成 0 条结果。 | `data/ChapterTextAccess.kt`、`LazyChapterContent.kt`；`ReaderChapterErrorsUiRobolectricTest` |
| 布局状态 | 未准备、准备中、恢复中、可阅读、失败五态；同代准备与同次视口恢复均完成才可保存位置。检查代次、提供器身份及目标章；失败后重新选择同章应推进代次，不能永久加载。 | `ui/ReaderLayoutSession.kt`、`ReaderScreen.kt`；`ReaderLayoutSessionTest` |
| 朗读按需加载 | 从当前章启动，不读取前面所有章节；后章按需接续，AI 最多预取两个后续语音分段。冷跳远章从角色检查点取继承信息，不回读全部前文。 | `ui/ReaderSpeechSession.kt`、`ReaderSpeech.kt`、`AiReaderSpeech.kt`、`AiSpeechChapterCursor.kt`；`AiSpeechChapterCursorRobolectricTest` |
| 朗读会话 | 初次空闲启动不多发暂停；播放中准备新位置先暂停保留节点，新源准备成功后才替换队列。书末快进没有新源时保留可恢复节点。取消或迟到服务回调不能起播，记笔记不暂停，引擎切换保持正确锚点。 | `ui/ReaderSpeechSession.kt`；`ReaderSpeechSessionRobolectricTest`、`ReaderNarrationInteractionRobolectricTest` |
| AI 完成徽标 | 读按章完成索引、资源属性和实际音频存在性，不重新读取、规划全书。全部分段存在才算完成；配置、正文、角色及继承检查点变化要正确失效。其他配置的记录可保留，但不能误用为当前配置的音频。清理后的旧任务不得回写缓存。 | `data/AiSpeechCache.kt`；`AiSpeechCacheRobolectricTest` |
| 封面 | 首页和书架共享有容量上限的图片缓存，热切换首帧复用；后台校验版本与解码。失效或淘汰不能立即销毁其他界面仍使用的图像。 | `ui/BookCoverCache.kt`、`LibraryScreens.kt`；`BookCoverCacheRobolectricTest`、`BookCoverNavigationRobolectricTest` |

`ReaderBookPageIndex` 工厂仍无 Android 生产阅读调用。继续使用当前章页码，不重新引入整书统计，也不把旧类或旧测试的存在当成已经接入的证据。

Android `Context`、`AtomicFile`、`Bitmap`、TTS／Service、音频焦点、系统媒体和 WorkManager 都需要桌面适配。Compose 状态转换与 Kotlin 契约可参考，但必须接入 DesktopReader 的实际加载、布局、视口恢复和播放器；Android 二进制页缓存不能直接在桌面复用。

## Windows 开发者的增量验收

以下均为**待 Windows 开发线程核对的项目**，不是已经完成的功能声明：

- [ ] 高频进度不重写全书架；重启恢复最新位置；结构变化后旧位置不覆盖新数据；退出和取消不丢已提交状态。
- [ ] 连续跨过至少 20 章后重型持有范围仍有界；返回释放章恢复相同 UTF-16 锚点；远章搜索／朗读不扩大准备窗口。
- [ ] 缺失、坏 UTF-8、合法空章和无 OCR 分别处理；修复后重试及目录重选同章能恢复；旧失败结果不能覆盖新目标。
- [ ] 初次启动、远章启动、书末快进、暂停后移动、引擎切换、记笔记、最小化／恢复均保持正确语音节点；读取失败不启动播放器。
- [ ] 语音徽标刷新不扫描全书；部分缓存不算整章完成；配置切换、正文编辑、角色变化、清理及晚返回请求均正确失效。
- [ ] 热切换阅读／书架／设置不先闪空封面；文件变更能刷新；缓存容量与仍在显示的图像生命周期正确。
- [ ] 还原进度真实、完成或失败后消失；还原期间其他书籍仍可上传；设置展示实际 Windows 下载目录。
- [ ] 在隔离 WebDAV 目录验证 Android 1.10.0 → Windows → Android 的最新进度、笔记、分组及角色，特别核对“旧 ZIP＋新 manifest”。

Android 1.10.0 的 1008 项测试、135 个类已通过，构建／lint 通过，见 [验证记录](verification.md)。新增 [Macrobenchmark](../../app/benchmark/README.md) 只完成 Android 工程与测试包编译，没有真机性能数据，也不是 Windows 性能基线。Windows 需记录实际机器、DPI、窗口、样本与冷／热耗时、内存和交互结果；不能用 Android 测试通过替代桌面验收。

本次交接更新不改变软件版本、不重打 APK、不发布 Windows 软件。保持现有 Android 两份更新清单及 APK 下载校验；Windows 产物和更新渠道继续独立。不要提交私人资料、凭据或其他开发对话尚未提交的工作。
