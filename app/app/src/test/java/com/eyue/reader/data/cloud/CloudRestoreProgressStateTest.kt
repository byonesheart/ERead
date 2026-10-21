package com.eyue.reader.data.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudRestoreProgressStateTest {
    @Test fun unknownWorkIsIndeterminateAndOnlyActualCallbacksSetTheFraction() {
        val state = CloudRestoreProgressState()
        val token = state.begin("读取云端书架")
        assertNull(state.progress!!.fraction)
        state.update(token, CloudOperationProgress("读取云端书籍信息 1 / 4", .25f))
        assertEquals(.25f, state.progress!!.fraction)
        state.update(token, CloudOperationProgress("保存书架与阅读记录"))
        assertNull(state.progress!!.fraction)
        assertTrue(state.finish(token))
        assertNull(state.progress)
    }

    @Test fun completingAnOperationRejectsAnyLateProgressCallback() {
        val state = CloudRestoreProgressState()
        val token = state.begin("读取书籍")
        state.finish(token)
        state.update(token, CloudOperationProgress("迟到的网络回调", .8f))
        assertNull(state.progress)
    }

    @Test fun anOldOperationCannotOverwriteOrFinishItsReplacement() {
        val state = CloudRestoreProgressState()
        val previous = state.begin("旧请求")
        val current = state.begin("新请求")
        state.update(current, CloudOperationProgress("新请求真实进度", .5f))
        state.update(previous, CloudOperationProgress("旧请求迟到进度", 1f))
        assertFalse(state.finish(previous))
        assertEquals(CloudOperationProgress("新请求真实进度", .5f), state.progress)
        assertTrue(state.finish(current))
        assertNull(state.progress)
    }
}
