package app.resound.scan

import java.io.File

/** 扫描识别出的一条待办。due 是模型给的 YYYY-MM-DD 或 YYYY-MM-DD HH:mm，可为空。 */
data class ScanTodo(val text: String, val due: String? = null)

/** 扫描识别结果；确认页展示，用户勾选后再写库。 */
data class ScanResult(
    val title: String,
    val summary: String,
    val todos: List<ScanTodo> = emptyList(),
    val tags: List<String> = emptyList(),
    val confidence: Double? = null,
)

sealed interface ScanRecognizeResult {
    /** [model] 是实际使用的模型名，写进事件的来源信息。 */
    data class Done(val result: ScanResult, val model: String) : ScanRecognizeResult

    /**
     * [visualUnsupported] 为 true 表示当前服务商/模型不接受图片或返回的不是可用 JSON，
     * 界面要明确提示换用支持视觉的模型，并允许手动填写。
     */
    data class Failed(val message: String, val visualUnsupported: Boolean = false) : ScanRecognizeResult
}

interface ScanRepository {
    /** 把图片交给当前选中的大模型识别。不会写库。 */
    suspend fun recognize(image: File): ScanRecognizeResult

    /** 确认后写库；返回写入的条目数（记忆算 1 条）。原图路径同时记在事件和待办上。 */
    suspend fun save(result: ScanResult, saveMemory: Boolean, addTodos: Boolean, image: File, model: String): Int
}
