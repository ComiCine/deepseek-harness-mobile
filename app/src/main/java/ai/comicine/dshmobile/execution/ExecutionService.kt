package ai.comicine.dshmobile.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import ai.comicine.dshmobile.HarnessApplication
import ai.comicine.dshmobile.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExecutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            (application as HarnessApplication).engine.cancelAll()
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("本地 Harness 任务正在运行"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            (application as HarnessApplication).engine.runningSessions.collectLatest { sessions ->
                if (sessions.isEmpty()) {
                    delay(10_000)
                    if ((application as HarnessApplication).engine.runningSessions.value.isEmpty()) stopSelf(startId)
                } else {
                    val summary = if (sessions.size == 1) "1 个本地会话正在执行" else "${sessions.size} 个本地会话正在执行"
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification(summary))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as HarnessApplication).engine.cancelAll()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, ExecutionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DeepSeek Harness")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "取消任务", cancel).build())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Harness tasks", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when on-device Harness execution is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "harness_execution"
        private const val NOTIFICATION_ID = 707
        private const val ACTION_CANCEL = "ai.comicine.dshmobile.execution.CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, ExecutionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
