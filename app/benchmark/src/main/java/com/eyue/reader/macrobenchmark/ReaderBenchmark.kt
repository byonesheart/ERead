package com.eyue.reader.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Exercises only the isolated benchmark installation through its normal visible UI. */
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(), startupMode = StartupMode.COLD, iterations = 5,
        setupBlock = { prepareDemoHome(); pressHome(); killProcess() }
    ) {
        startActivityAndWait()
        requireNode(By.text(DEMO_TITLE), "示例书未加载；本次启动测量失败")
    }

    @Test fun warmHomeShelfSettingsSwitches() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 5,
        setupBlock = {
            prepareDemoHome()
            switchTabs()
        }
    ) { repeat(3) { switchTabs() } }

    @Test fun openDemoNovel() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 5,
        setupBlock = { prepareDemoHome() }
    ) { openDemo() }

    @Test fun scrollDemoNovel() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 5,
        setupBlock = {
            prepareDemoHome()
            openDemo()
            check(device.click(device.displayWidth / 2, device.displayHeight / 2))
            requireNode(By.text("目录"), "找不到阅读目录").click()
            requireNode(By.text("第一章 雨停之后"), "找不到示例第一章").click()
            requireReaderPage()
        }
    ) {
        val x = device.displayWidth / 2
        repeat(3) {
            check(device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 30))
            device.waitForIdle()
        }
        requireReaderPage()
    }

    private fun MacrobenchmarkScope.prepareDemoHome() {
        startActivityAndWait()
        // Consent is accepted only through the same checkbox and button as a normal first launch.
        if (device.wait(Until.hasObject(By.text("同意并进入")), 1_000)) {
            val checkbox = requireNode(By.clazz("android.widget.CheckBox"), "找不到首次协议勾选框")
            if (!checkbox.isChecked) checkbox.click()
            requireNode(By.text("同意并进入").enabled(true), "协议未能正常确认").click()
        }
        // The previous iteration may have left the reader open. Return through its normal back flow.
        if (!device.wait(Until.hasObject(By.text("阅读")), 1_000)) {
            device.pressBack()
            if (!device.wait(Until.hasObject(By.text("阅读")), 1_000)) device.pressBack()
        }
        requireNode(By.text("阅读"), "无法进入阅读首页，不能绕过协议或使用空书架测量").click()
        requireNode(By.text("小说"), "找不到小说模式").click()
        requireNode(By.text(DEMO_TITLE), "缺少默认示例书，请仅重置 com.eyue.reader.benchmark 后重试")
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.switchTabs() {
        requireNode(By.text("书架"), "找不到书架入口").click()
        requireNode(By.text(DEMO_TITLE), "书架未显示示例书")
        requireNode(By.text("设置"), "找不到设置入口").click()
        requireNode(By.text("朗读引擎"), "设置页面未加载")
        requireNode(By.text("阅读"), "找不到阅读入口").click()
        requireNode(By.text(DEMO_TITLE), "阅读首页未恢复")
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openDemo() {
        requireNode(By.text(Pattern.compile("开始阅读|继续阅读")), "找不到示例书阅读按钮").click()
        requireReaderPage()
    }

    private fun MacrobenchmarkScope.requireReaderPage() {
        val pattern = Pattern.compile(".*本章 ([1-9][0-9]*) / ([1-9][0-9]*) 页.*")
        val page = requireNode(By.text(pattern), "正文加载或章内分页未完成")
        val match = pattern.matcher(page.text.orEmpty())
        check(match.matches() && match.group(1).toInt() <= match.group(2).toInt()) { "章内页码无效，不能作为有效阅读性能结果" }
        val failed = listOf("正文准备失败", "无法读取", "文件读取失败", "排版失败").any {
            device.hasObject(By.textContains(it).pkg(PACKAGE))
        }
        check(!failed) { "正文准备失败，不能作为有效阅读性能结果" }
    }

    private fun MacrobenchmarkScope.requireNode(selector: BySelector, message: String): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector.pkg(PACKAGE)), 10_000)) { message }

    companion object {
        private const val PACKAGE = "com.eyue.reader.benchmark"
        private const val DEMO_TITLE = "雨后的旧书店"
    }
}
