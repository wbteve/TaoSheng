package app.resound.ui.scan

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.resound.scan.ScanRecognizeResult
import app.resound.scan.ScanRepository
import app.resound.scan.ScanResult
import app.resound.scan.ScanTodo
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.io.File

/**
 * 扫描：拍照或从相册选图 → 当前选中模型识别 → 确认页勾选存入记忆/待办。
 * 失败时明确提示换视觉模型，并允许手动填写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(repo: ScanRepository, onClose: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var image by remember { mutableStateOf<File?>(null) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var model by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var saveMemory by remember { mutableStateOf(true) }
    var addTodos by remember { mutableStateOf(true) }
    var manualTitle by remember { mutableStateOf("") }
    var manualTodos by remember { mutableStateOf("") }

    fun recognize(file: File) {
        image = file
        result = null
        error = null
        manual = false
        busy = true
        scope.launch {
            when (val outcome = runCatching { repo.recognize(file) }.getOrElse {
                ScanRecognizeResult.Failed("识别失败，请重试或手动填写")
            }) {
                is ScanRecognizeResult.Done -> {
                    result = outcome.result
                    model = outcome.model
                    manualTitle = outcome.result.title
                    manualTodos = outcome.result.todos.joinToString("\n") { todo -> todo.due?.let { "${todo.text}（$it）" } ?: todo.text }
                }
                is ScanRecognizeResult.Failed -> {
                    error = outcome.message
                    manual = outcome.visualUnsupported
                }
            }
            busy = false
        }
    }

    val cameraFile = remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraFile.value?.let(::recognize) else cameraFile.value?.delete(); cameraFile.value = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val file = runCatching { copyToScanFile(context, uri) }.getOrNull()
            if (file != null) recognize(file) else error = "图片读取失败，请换一张重试"
        }
    }

    fun takePhoto() {
        val file = newScanFile(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraFile.value = file
        runCatching { camera.launch(uri) }.onFailure {
            file.delete(); cameraFile.value = null
            Toast.makeText(context, "没有可用的相机，请从相册选择", Toast.LENGTH_SHORT).show()
        }
    }

    fun save() {
        val current = result ?: if (manual) manualResult(manualTitle, manualTodos) else null
        if (current == null) { Toast.makeText(context, "没有可保存的内容", Toast.LENGTH_SHORT).show(); return }
        if (!saveMemory && !addTodos) { Toast.makeText(context, "请至少勾选一项", Toast.LENGTH_SHORT).show(); return }
        val file = image ?: return
        busy = true
        scope.launch {
            val saved = runCatching { repo.save(current, saveMemory, addTodos, file, model.ifBlank { "扫描" }) }
                .getOrElse { Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show(); 0 }
            busy = false
            if (saved > 0) {
                Toast.makeText(context, "已保存 $saved 条，来源：扫描", Toast.LENGTH_SHORT).show()
                onClose()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.background, dragHandle = {
        Box(Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator))
    }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("扫描", style = RsType.title, color = c.text)
            Text("拍纸、白板、截图或收据，交给当前选中的大模型整理成记忆和待办。", style = RsType.subhead, color = c.textSecondary)

            when {
                busy -> RsCard(padding = PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在识别图片…", style = RsType.body, color = c.text)
                    }
                }
                else -> {
                    if (image == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("拍照", filled = true) { takePhoto() }
                            Pill("从相册选择", filled = false) {
                                runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                    .onFailure { Toast.makeText(context, "没有可用的相册", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                    error?.let {
                        RsCard(padding = PaddingValues(14.dp), color = c.critical.copy(alpha = 0.08f)) {
                            Text(it, style = RsType.subhead, color = c.critical)
                        }
                    }
                    if (image != null && result == null && !manual) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("重新选择", filled = false) { image = null; error = null; manual = false; result = null }
                            Pill("手动填写", filled = false) { manual = true; manualTitle = ""; manualTodos = "" }
                        }
                    }
                    if (manual && result == null) {
                        Text("手动填写", style = RsType.section, color = c.textSecondary)
                        LabeledField("标题", manualTitle, { manualTitle = it }, singleLine = true)
                        LabeledField("待办（每行一条）", manualTodos, { manualTodos = it }, singleLine = false)
                        Text("想带时间就写成“内容（2026-11-10 15:00）”。", style = RsType.caption, color = c.textTertiary)
                    }
                    result?.let { r ->
                        RsCard(padding = PaddingValues(16.dp)) {
                            Text(r.title.ifBlank { "扫描记录" }, style = RsType.headline, color = c.text)
                            if (r.summary.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(r.summary, style = RsType.body, color = c.textSecondary)
                            }
                            if (r.tags.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    r.tags.forEach { tag ->
                                        Text("#$tag", style = RsType.caption, color = c.accent,
                                            modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 9.dp, vertical = 3.dp))
                                    }
                                }
                            }
                            r.confidence?.let { Text("识别把握 ${(it * 100).toInt()}%", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp)) }
                        }
                        if (r.todos.isNotEmpty()) {
                            Text("识别到的待办", style = RsType.section, color = c.textSecondary)
                            RsCard(padding = PaddingValues(vertical = 4.dp)) {
                                r.todos.forEachIndexed { index, todo ->
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
                                        Text("${index + 1}.", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(top = 1.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(todo.text, style = RsType.body, color = c.text)
                                            todo.due?.let { Text(it, style = RsType.caption, color = c.accent) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (result != null || manual) {
                        Spacer(Modifier.height(2.dp))
                        CheckRow("存为记忆", saveMemory) { saveMemory = it }
                        CheckRow("加为待办", addTodos) { addTodos = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Pill("保存", filled = true) { save() }
                            Pill("取消", filled = false) { onClose() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().springClick { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.textTertiary))
        Text(text, style = RsType.body, color = c.text)
    }
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = false) {
    val c = Rs.colors
    Column {
        Text(label, style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(value = value, onValueChange = onChange, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            maxLines = if (singleLine) 1 else 8,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(c.card).padding(12.dp),
            decorationBox = { inner -> if (value.isEmpty()) Text("", style = RsType.body); inner() })
    }
}

/** 手动填写：标题 + 每行一条待办；支持“内容（时间）”。 */
private fun manualResult(title: String, text: String): ScanResult? {
    val todos = text.lines().map { it.trim() }.filter { it.isNotBlank() }.map { line ->
        val match = Regex("""^(.*?)[（(]([^）)]+)[）)]$""").find(line)
        if (match != null) ScanTodo(match.groupValues[1].trim(), match.groupValues[2].trim())
        else ScanTodo(line)
    }.filter { it.text.isNotBlank() }
    if (title.isBlank() && todos.isEmpty()) return null
    return ScanResult(title.trim().ifBlank { "扫描记录" }.take(80), "", todos)
}

private fun newScanFile(context: Context): File {
    val dir = File(context.filesDir, "scans").apply { mkdirs() }
    return File(dir, "scan-${System.currentTimeMillis()}.jpg")
}

private fun copyToScanFile(context: Context, uri: Uri): File {
    val file = newScanFile(context)
    context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    } ?: error("cannot open image")
    return file
}
