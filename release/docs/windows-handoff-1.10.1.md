# Windows 增量交接：Android 1.10.1

更新日期：2026-10-22。本文接续 [完整交接](windows-handoff.md) 和 [1.9.9～1.10.0 增量交接](windows-handoff-1.10.0.md)，记录本次改动及 Windows 需要核对的行为。构建验证与发布状态由本次发布记录补充，本文不声明 Windows 已完成适配。

## 字体必须使用实际字体资源

此前“宋体／黑体”映射到 Android 通用 `serif`／`sans-serif`，中文可能再次回退到厂商主题字体，造成黑体选择无变化，宋体的字形和度量也不稳定。本次分别内置未修改的 Adobe 思源宋体、思源黑体 CN Regular，只有“系统”继续跟随系统字体；导入字体仍优先。

- 保留现有配置值 `fontName="系统宋体"`／`"系统黑体"`，只是将解析结果改为内置字体，不批量改写用户配置。
- `ui/ReaderFonts.kt` 提供 `readerFontFamily` 和内置资源映射；实际显示与 `ui/ReaderLayoutPreparation.kt` 的测量使用相同字体，字号、行距、段距和字距仍来自 `ReaderSettings`。
- `ui/ReaderPageCache.kt` 的平台身份使用对应字体的实际度量，分页算法版本改为 10，让旧系统回退字体产生的缓存失效一次。继续按章计页，不恢复整书统计。
- 字体文件、上游固定版本、哈希和许可证见 [内置字体来源](fonts.md)。Windows 可以使用同一字体资源，但要随产物保留原许可证；不能用 Windows 安装的商业字体代替后再打包。

Windows 应验证自己的文本渲染和测量实际加载同一字体，不能只显示了正确的选项名称。Android 二进制分页缓存不跨平台复用。

## 软件主题与阅读配置分开

外部“设置 → 主题”增加“经典／像素”，属于软件整体外观；阅读内原“主题”改名为“配置”，仍保存小说／漫画各自的阅读背景、字体和排版。

本地 `LibraryState` 新增：

```json
{
  "appTheme": "CLASSIC"
}
```

可选值为 `CLASSIC`、`PIXEL`，缺失或未知值回退经典。该字段位于 `library.json` 根级，**仍为 version 13**；不加入 `ReaderSettings`、`ReaderThemeProfile` 或高频 `reading_positions.json`。切换软件主题不能覆盖书内配置，也不能把阅读配置切换变成软件换肤。

- `data/AppTheme.kt` 定义 `AppThemeStyle`；`data/LibraryRepository.kt` 负责持久化，`ReaderViewModel.setAppTheme` 只更新该字段。
- `ui/AppTheme.kt` 的 `EReadTheme` 提供主题明暗配色、组件形状和标签样式，`appShape` 为主要容器选择圆角或阶梯像素角；不是只改主题按钮的外观。
- 像素主题支持浅／深色，明暗仍由既有 `themeMode` 决定；开启墨水屏时优先使用黑白高对比配色。
- `ui/AppThemeSettings.kt` 为主题选择页，主界面、书架和设置的接入见 `EReadApp.kt`、`LibraryScreens.kt`、`SettingsPreferences.kt`。

WebDAV catalog／manifest／归档 metadata 仍为 **version 1**，本次没有增加云端 schema。`appTheme` 是本机软件偏好；不要为了同步该字段擅自改变原有书籍备份协议。

## 启动静默检查更新

Android 在用户同意协议、进入应用后自动检查更新，无自动弹窗或自动失败提示。会话状态由 `ui/AppUpdateSession.kt` 的 ViewModel 管理，同一会话内重组／Activity 重建不重复发起启动检查，手动检查复用进行中的请求。

- 发现已发布的新版本时，设置导航图标显示小红点。
- 点击或进入设置即消除本次红点，保留更新结果；“关于”继续显示“待更新”。已在设置内时才返回的结果也视为已读。
- 进入“关于”直接使用已取得的结果展示 GitHub 和网盘下载链接，不要求再点一次检查；手动检查和错误提示继续保留。
- 自动检查失败时不阻止阅读，不把失败伪装成“已是最新版本”。

Windows 应使用自己的版本和更新渠道，不能将 Android APK 或 Android versionCode 当作桌面更新。行为可参考 `AppUpdateSession.kt`、`EReadApp.kt` 和 `InformationPages.kt`，平台生命周期需分别适配。

## 其他界面与展示变化

- 阅读“界面”面板将“模式”放到背景下一行；仅横向翻页模式展示覆盖／滑动／仿真，滚动和上下模式不展示该组。
- 阅读配置栏名称为“配置”；新建、选中和自动保存语义沿用，不新建另一套配置数据。
- 未提供作者时统一展示“佚名”，实现为 `data/BookPresentation.kt` 的 `Book.displayAuthor`。原始 `Book.author` 及源文件元数据不因此改写，避免影响书籍身份和云端重复判断。
- 书架大图布局移除封面下的“云端书籍”说明；未下载书籍在封面显示“待下载”，传输进行中仍显示实际进度。`cloudOnly`、下载操作和来源身份语义不变。

## Windows 待验收

- [ ] 系统字体、内置宋体、内置黑体和导入字体各自生效，测量与显示同源；换字体不意外改字号、间距和原文锚点。
- [ ] 旧配置可恢复，旧分页缓存正确失效；设置切换后仍保持按章计算和有界章节加载。
- [ ] 软件主题切换实际改变整体界面，重启后保留；小说与漫画阅读配置不被覆盖；深色与墨水屏仍正确。
- [ ] `appTheme` 缺失／未知值兼容，独立进度叠加规则沿用 1.10.0；不变更既有 WebDAV 协议。
- [ ] 启动检查无弹窗且不阻塞，设置红点可消除；“关于”保留待更新和现成下载链接；手动检查失败可见。
- [ ] 空作者展示佚名，待下载提示在封面，大图下方无重复提示；上传／下载和重复书籍识别不受展示改名影响。

本次只补充 Android 改动的桌面交接，没有修改、编译或发布 Windows 工作。具体构建、测试和设备验证结果以 [验证记录](verification.md) 为准；不能用 Android 结果替代 Windows 验收。
