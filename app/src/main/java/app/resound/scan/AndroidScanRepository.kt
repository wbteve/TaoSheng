package app.resound.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.resound.data.MemoryDao
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.digest.DigestPrompt
import app.resound.digest.LlmClient
import app.resound.digest.LlmError
import app.resound.digest.LlmException
import app.resound.digest.LlmPreset
import app.resound.digest.allowsEmptyKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64

/**
 * 扫描识别走用户当前选中的服务商和密钥（[credentials] 由 AndroidDigestSettings 提供），
 * 与整理/问问共用 LlmClient，不另存一套密钥。
 */
class AndroidScanRepository(
    private val client: LlmClient,
    private val credentials: () -> Pair<LlmPreset, String>,
    private val dao: MemoryDao,
    private val onSaved: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : ScanRepository {

    override suspend fun recognize(image: File): ScanRecognizeResult = withContext(Dispatchers.IO) {
        if (!image.isFile || image.length() == 0L) return@withContext ScanRecognizeResult.Failed("图片读取失败，请重新选择")
        val (preset, key) = credentials()
        if (key.isBlank() && !preset.allowsEmptyKey()) {
            return@withContext ScanRecognizeResult.Failed("还没填写大模型密钥，请到设置 → 整理模型填写后再扫描")
        }
        val data = try { jpegDataUrl(image) } catch (_: Exception) {
            return@withContext ScanRecognizeResult.Failed("图片读取失败，请换一张图片重试")
        }
        val today = LocalDate.now()
        val reply = try {
            client.chatVisionJson(preset, key, ScanPrompt.system(today, LocalTime.now()), "请识别这张图片并按要求输出 JSON。", data)
        } catch (e: LlmException) {
            val unsupported = e.status in setOf(400, 404, 415, 422) || e.type == LlmError.Format
            val message = when {
                unsupported -> UNSUPPORTED
                e.type == LlmError.Auth -> "大模型密钥无效，请到设置 → 整理模型检查"
                e.type == LlmError.Quota -> "大模型余额不足或额度已用完，请充值后重试"
                e.type == LlmError.Network -> "网络连接失败，请检查网络后重试"
                e.type == LlmError.RateLimit -> "请求太频繁，请稍后再试"
                else -> "识别失败：${e.detail}"
            }
            return@withContext ScanRecognizeResult.Failed(message, unsupported)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext ScanRecognizeResult.Failed("识别失败，请重试或手动填写")
        }
        val result = try { ScanPrompt.parse(reply.content) } catch (_: Exception) {
            return@withContext ScanRecognizeResult.Failed(UNSUPPORTED, true)
        }
        ScanRecognizeResult.Done(result, reply.actualModel ?: preset.model)
    }

    override suspend fun save(result: ScanResult, saveMemory: Boolean, addTodos: Boolean, image: File, model: String): Int =
        withContext(Dispatchers.IO) {
            if (!saveMemory && !addTodos) return@withContext 0
            val at = now()
            val zone = ZoneId.systemDefault()
            val path = image.absolutePath
            val items = result.todos.map { todo ->
                val dueAt = todo.due?.let { DigestPrompt.parseDue(it, zone) }
                MemoryItemEntity(
                    eventId = null, category = "待办", kind = "我要做", content = todo.text.trim(),
                    dueText = todo.due, dueAt = dueAt, who = null, lineId = null, status = "Accepted",
                    createdAt = at, statusChangedAt = at, noMerge = true, origin = "scan",
                    sourceText = path, strongReminder = false,
                )
            }
            val event = if (saveMemory) MemoryEventEntity(
                startAt = at, endAt = at, scene = "扫描", title = result.title.ifBlank { "扫描记录" },
                summary = result.summary, pointsJson = JSONArray(result.tags).toString(), peopleJson = "[]",
                place = null, emotion = null, intensity = null, important = false, createdAt = at,
                model = model, sourceKind = "扫描", sourcePath = path,
            ) else null
            val count = dao.saveScanned(event, if (addTodos) items else emptyList())
            onSaved()
            count
        }

    /** 统一转成 JPEG 再 base64：部分服务商不接受 PNG/HEIC，也避免原图几十 MB 撑爆请求。 */
    private fun jpegDataUrl(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("decode failed")
        val scaled = if (maxOf(bitmap.width, bitmap.height) > 1600) {
            val ratio = 1600f / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
        } else bitmap
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()
            out.toByteArray()
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        const val UNSUPPORTED = "当前服务商不支持图片识别，请换用支持视觉的模型"
    }
}
