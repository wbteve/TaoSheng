package app.resound.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.resound.capture.CaptureNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 设置页的“测试通知”：5 秒后走和待办提醒同一条 AlarmManager→通知渠道路径，
 * 便于用户自测通知权限、渠道重要级和后台限制。
 */
object NotificationTest {
    private const val REQUEST = 9901
    const val DELAY_MS = 5_000L

    fun schedule(context: Context) {
        val intent = Intent(context, TestNotificationReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + DELAY_MS
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun canPost(context: Context) = CaptureNotifications.permitted(context) &&
        context.getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel(LocalNotifier.TODO_CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
}

class TestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                LocalNotifier(context.applicationContext, LocalNotifier.TODO_CHANNEL)
                    .send("测试通知", "如果你看到这条，通知权限和渠道都正常。想更准点响，请在设置里允许“闹钟与提醒”和后台运行。",
                        "resound://today?open=todos", PushTopic.TodoReminder)
            } catch (_: Exception) {
                // 权限被关时通知本来就不会出现；设置页会显示原因，这里不弹崩溃。
            } finally {
                pending.finish()
            }
        }
    }
}
