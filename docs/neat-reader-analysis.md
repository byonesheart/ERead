# Neat Reader APK 对比记录

日期：2026-10-13。用户提供 `base.apk`，包名 `com.gzhi.neatreader.r2.main`，版本 8.2.0 / 191。

APK SHA-256：`93F91A279AAD831B6810ABCDBBC9FE92A07C03E5AEA97ED6E056FF2BE018874B`。

## 分析范围与证据

只读取 ZIP 内的资产和 DEX；未执行 APK 或其中的 JavaScript。静态格式化及反编译输出留在忽略的 `.tools/neat-analysis`，没有加入 E阅的源码或安装包。JADX 部分方法反编译失败，未使用这些失败方法作结论。

| 已确认行为 | 本地分析位置 |
| --- | --- |
| MOBI 解析器有仅元数据的打开路径，读取 EXTH、书名、封面后可返回，跳过正文解析。 | `assets/js-mobi/loaders.readable.js:6936` |
| Mobile MobiFetcher 在 `BOOK_META_ONLY` 或 preloader 模式下启用该路径。 | `assets/bundle.readable.js:40530` |
| 完整打开仍解压正文，处理阅读片段的 DOM；不能称为逐章懒解压。 | `assets/js-mobi/loaders.readable.js` 的 `parseMobiSections`、正文解析函数 |
| 已处理章节存在内存 sectionCache；章节渲染时才解析图片资源引用并创建可显示地址。 | `assets/bundle.readable.js:40572` 至章节渲染方法末尾 |
| MOBI 目录解析使用 INDX、TAGX、CTOC，包含标题、正文位置及父子关联。 | `assets/js-mobi/loaders.readable.js:6527` |
| Android 正常日期的 TXT 导入采用旧版 1.0 匹配器；1.1 开关的日期门槛是 2099-12-31。 | `assets/bundle.readable.js` 的 universal-txt 初始化及 format 路径 |
| Android MOBI 打开不调用原生 EPUB Publication 解析，交给 WebView 内的阅读脚本；服务端 Fetcher 提供源文件流。 | `MainActivity.java:1641`、`AbstractServer.java`、`TxtFetcher.java` |

这解释了“加入书架很快”与“全文处理完成”的区别：Neat 存在先取得元数据、后处理正文的路径，并直接利用浏览器排版和按需图片。E阅此前在导入完成前处理全部章节、源格式、图片和文字缓存；翻页模式还准备当前屏幕布局。APK 静态分析没有证明用户当时的导入必然使用哪一个分支，也没有得到同文件、同手机的速度对比，不能保证等同速度或给出倍数。

## E阅 1.5.2 的独立实现

1. 优先读取标准 MOBI6 二进制目录，直接用内置标题及正文位置分章，跳过 HTML 链接目录和标题回退扫描。无效、缺失、不支持的可选索引及仅脚注目录仍回退到现有 HTML 识别。目录记录、数量、变长整数及偏移有上限；取消操作可中止。
2. 沿用一次 UTF-8 → UTF-16 位置转换，保留多字节文字、前置内容及最后一章尾部。小说目录按正文顺序展开，未增加层级目录 UI。
3. 正文空白归一化只记录图片和源格式的端点。没有端点的章节不分配字符位置数组。1000 万 UTF-16 字符不再因该步骤固定分配约 40 MB 的位置数组，同时避免创建供 trim 使用的整篇中间字符串。对每个字符的旧行为做随机及边界回归，书签、笔记使用的文字位置语义不变。
4. 默认文本规则使用整行、横向空白及有长度上限的标题，增加常见中文单位与英文 Part / Section / 罗马编号。保留无空格的短中文标题；不增加裸数字和括号编号规则，以减少注释误判。仅自动迁移与旧默认规则完全一致的设置，自定义规则保留。短正文和标题仍可能有文字上的歧义，源目录优先能减少这种猜测。
5. 新 MOBI 缓存版本为 6；旧版在原文件可读取时更新一次并迁移进度及标注。EPUB 的缓存版本和已完成的解析不因此重做。

没有将整个导入改成仅元数据完成：用户此前要求导入时准备正文，完成后直接阅读，现有待加载书卡片继续展示完整准备过程。也没有加入 Neat 的脚本或切换到 WebView，避免改变原生文本选区、朗读和精确标注的行为。

二进制目录实现依据公开格式资料独立编写：[KindleUnpack INDX 格式说明及实现](https://github.com/kevinhendricks/KindleUnpack/blob/master/lib/mobi_index.py)、[MOBI 头字段](https://github.com/kevinhendricks/KindleUnpack/blob/master/lib/mobi_header.py)、[libmobi 索引实现](https://github.com/bfabiszewski/libmobi/blob/master/src/index.c)。没有复制上述项目或 Neat Reader 的实现代码。

## 验证范围

新增回归覆盖内置目录优先、多字节边界、损坏索引回退、脚注排除、取消、真实公开 MOBI、长正文映射空间，以及默认标题规则的正反例。最终编译、测试数量和 APK 校验值见 [verification.md](verification.md)。

桌面 JVM 测试不能替代 Android 手机上完整导入计时；Android 的 `Html.fromHtml` 仍参与文字转换，源格式与全部正文仍需解析。实际 10 MB MOBI 文件尚未提供，本轮不声称已在该文件或用户手机上复现并解决所有卡死情况。
