# 内置阅读字体来源

阅读器的“宋体”和“黑体”分别使用未修改的 Adobe 思源宋体、思源黑体 CN Regular 地区子集。它们有自己的中文字形和度量，不依赖手机厂商对 Android 通用 serif／sans-serif 名称的替换。“系统”选项仍跟随系统字体，用户导入字体优先。

两个文件均按 SIL Open Font License 1.1 随软件分发；原版权、字体内部名称和许可原文保留。资源文件名仅用于 Android 打包。字体不提供书籍内容。

| 资源 | 上游文件与固定版本 | SHA-256 |
| --- | --- | --- |
| `app/app/src/main/res/font/reader_song.otf` | [SourceHanSerifCN-Regular.otf](https://github.com/adobe-fonts/source-han-serif/blob/7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf)，11,626,108 字节 | `3754EA669C530E2473354F8F6D9F79680A44D7E26EC7D00EEABEE4A7E0753C5D` |
| `app/app/src/main/res/font/reader_hei.otf` | [SourceHanSansCN-Regular.otf](https://github.com/adobe-fonts/source-han-sans/blob/a4f7cf94edfb9d7ffbdfc4841de276358bd7e0f2/SubsetOTF/CN/SourceHanSansCN-Regular.otf)，8,429,224 字节 | `E2BC8A2E7F37474B774FFF8DB758681ECE40BB6947A90D571BCE9DD60671A8E4` |

许可证见 [思源宋体](licenses/source-han-serif-LICENSE.txt)、[思源黑体](licenses/source-han-sans-LICENSE.txt)，同样随 APK 在“开源相关”中离线提供。Android 文本测量和实际显示共用同一资源字体，字号、行距、字距仍取用户设置；分页缓存会失效一次，以免沿用旧的系统回退字体排出的页码。

## 1.10.2 排版校正

字体文件保持未修改。内置宋体／黑体以当前系统字体的“国田中日月山水书读”九个汉字为参照，测量实际墨迹长边均值及字符步进；先按墨迹比例均匀缩放字号（限制为 0.8–1.15），再通过额外字距补偿维持接近系统参照的字符步进，不横向压缩字形。显示和分页测量共用同一结果；系统与用户导入字体不套用此内置字体校正。不同字形仍可能有视觉差异。

默认字距改为 0.5 sp，只用于新建或缺失该项的设置；已明确保存的字距（包括 0）继续保留。用户字号与字距仍是配置值，实际绘制在其上应用内置字体校正。分页算法版本为 11，旧页表按需失效，继续按章计算。Windows 需使用自己的渲染引擎重新测量，不能直接套用 Android 的字体度量或分页缓存。
