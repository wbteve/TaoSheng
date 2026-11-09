package app.resound.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class ScanPromptTest {
    @Test
    fun parsesStructuredJsonAndIgnoresCodeFence() {
        val raw = """
            ```json
            {"title":"周会纪要","summary":"确定周五上线","todos":[{"text":"补测试","due":"2026-11-10 15:00"},{"text":"发通知","due":null}],"tags":["工作","上线"],"confidence":0.86}
            ```
        """.trimIndent()
        val result = ScanPrompt.parse(raw)
        assertEquals("周会纪要", result.title)
        assertEquals("确定周五上线", result.summary)
        assertEquals(2, result.todos.size)
        assertEquals("补测试", result.todos[0].text)
        assertEquals("发通知", result.todos[1].text)
        assertNull(result.todos[1].due)
        assertEquals(listOf("工作", "上线"), result.tags)
        assertEquals(0.86, result.confidence ?: 0.0, 0.001)
    }

    @Test
    fun malformedJsonThrowsSoUiCanOfferManualInput() {
        val error = runCatching { ScanPrompt.parse("这不是 JSON") }.exceptionOrNull()
        assertTrue(error != null)
    }

    @Test
    fun unparseableDueBecomesNullInsteadOfGuessing() {
        assertNull(ScanPrompt.normalizeDue("下周有空再说", ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-11-10", ScanPrompt.normalizeDue("2026-11-10", ZoneId.of("Asia/Shanghai")))
    }
}
