# Android 性能基线

此模块为独立 Macrobenchmark 测试驱动，覆盖冷启动、阅读/书架/设置之间的热切换、打开示例小说和从第一章开始滚动。每项运行 5 次，切换场景先预热封面。冷启动记录 `StartupTimingMetric` 的首次显示时间（TTID / `timeToInitialDisplayMs`）；其余场景记录 `FrameTimingMetric`。没有加入 `reportFullyDrawn()`，因此不测量完整显示时间 TTFD。等待示例书出现仅用于确认场景成功，不能把这段等待当作 TTFD。未向生产代码加入测试入口，也未伪造完整绘制时刻。

**目前只有测试工程，不代表已经建立真机性能基线。** 编译通过、桌面 Robolectric 或模拟器结果均不能作为手机性能结论。

## 构建与运行

从仓库根目录执行，仅构建，不需要设备：

```powershell
./app/scripts/benchmark.ps1 -BuildOnly
```

连接一台已开启 USB 调试并授权的 Android 14 或更新版本真机，保持解锁，关闭其他高负载任务，使用一致的亮度、刷新率和温度条件。执行：

```powershell
./app/scripts/benchmark.ps1 -Serial '设备序列号' -Reset
```

`-Reset` 只清理 `com.eyue.reader.benchmark` 的专用测试书库，随后通过正常首次协议界面的勾选框与同意按钮进入、自动创建原有示例书。不访问正式安装 `com.eyue.reader` 的书籍与设置。建议每组对比都使用此选项，保持默认滚动模式及相同测试数据。缺少示例书、协议无法确认、页面打不开或正文准备失败时，测试会失败，不能静默跳过并产生有效成绩。

测试目标由 `:app:assembleBenchmark` 生成，使用独立包名、调试签名、非 debuggable 配置及仅此构建的 `<profileable>`；测试驱动由 `:benchmark:assembleBenchmark` 生成。正式应用仍支持 Android 8.0，正式构建的 R8 设置不变。

测量使用 `CompilationMode.None()`，不生成或安装 Baseline Profile。测试模块限定 Android 14+，避免更早系统重置编译状态时重新安装应用、丢失已确认协议与示例数据。以后评估 Room、R8、Baseline Profiles 时，应分别测量并记录编译模式，不将不同条件下的数字混合比较。[官方 Macrobenchmark 设置及运行说明](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。

## 结果记录

输出位于 `app/benchmark/build/outputs/`，包括连接设备测试的报告、JSON 指标及每次迭代的 Perfetto trace。报告的具体子目录由 Android Gradle Plugin 生成。

每次保留：源码提交号、应用版本、APK 摘要、设备型号、Android/ART 版本、刷新率、分辨率、亮度、电量与温度、编译模式、是否重置测试库，以及全部迭代数据。先观察中位数和尾部分布，再打开异常迭代 trace 查找原因。现有示例书只能作为固定小数据集，后续应另建脱敏的大书、长目录及漫画场景；不得把私人书籍打包进公开测试工程。

本工程固定使用兼容现有 SDK 35 / Kotlin 2.1 的 Benchmark 1.4.1，不在此轮附带升级工具链。已核对官方 Google Maven AAR 元数据：该版本要求 `minCompileSdk=34`、最低 AGP 8.1.1；项目为 SDK 35、AGP 8.9.2。ProfileInstaller 只加入 benchmark 构建依赖。[Benchmark 版本记录](https://developer.android.com/jetpack/androidx/releases/benchmark)、[官方性能测试示例工程](https://github.com/android/performance-samples/tree/main/MacrobenchmarkSample)。

本脚本不关闭 Macrobenchmark 的配置错误检查，不把模拟器、低电量或 debuggable 测量错误降级为有效结果。[官方测量参数与错误说明](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args)。
