package com.pixivscraper

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 下载前台服务：常驻通知 + 前台优先级，防止系统在后台 / 锁屏时回收进程。
 *
 * - 搜索 / 筛选阶段：通知只显示文字「正在搜索与筛选作品…」，无进度条
 * - 下载图片阶段：进度条显示「已下载 / 目标」
 * - 通知按钮「停止」= 置 RunState.stopFlag，引擎会优雅停止
 */
class ScrapeService : Service() {

    companion object {
        private const val CHANNEL_ID = "pixdo_run"
        private const val NOTIF_ID = 1001
        private const val DONE_CHANNEL_ID = "pixdo_done"
        private const val DONE_NOTIF_ID = 1002
        private const val ACTION_STOP = "com.pixivscraper.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ScrapeService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScrapeService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 点通知里的「停止」：请求引擎停止；服务会在运行状态清零后自行收起
            RunState.stopFlag = true
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireWakeLock()
        if (monitorJob == null) {
            monitorJob = scope.launch { monitor() }
        }
        return START_NOT_STICKY
    }

    /** 轮询运行状态，变化时刷新通知；任务结束后收起通知、弹「完成通知」并停止服务 */
    private suspend fun monitor() {
        var lastVersion = Int.MIN_VALUE
        val wasRunning = RunState.running
        while (RunState.running) {
            if (RunState.version != lastVersion) {
                lastVersion = RunState.version
                notify(buildNotification())
            }
            delay(400)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (wasRunning) postDoneNotification()
        releaseWakeLock()
        stopSelf()
    }

    /**
     * 部分系统（尤其国产 ROM）在锁屏 / 后台会休眠进程的定时器与网络调度，
     * 表现为「后台筛选卡住、但下载仍在跑」。持 PARTIAL_WAKE_LOCK 保证任务持续推进，
     * 任务结束 / 服务销毁时释放。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixdo:run").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4 小时上限，防止异常情况下泄漏
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun notify(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ 未授予通知权限：前台服务照常运行，只是不显示通知
        }
    }

    /** 任务结束后弹一条「完成 / 停止 / 中断」通知，可点回 App */
    private fun postDoneNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val downloaded = RunState.endDownloaded
        val skipped = RunState.endSkipped
        val reason = RunState.endReason

        val title: String
        val text: String
        when {
            RunState.stopFlag -> {
                title = "任务已停止"
                text = "已下载 $downloaded 个作品"
            }
            !reason.isNullOrEmpty() -> {
                title = "任务中断"
                text = reason.take(80)
            }
            else -> {
                title = "下载完成"
                text = if (downloaded == 0) {
                    "本次未下载新作品（可能均已下载或未通过筛选）"
                } else {
                    buildString {
                        append("本次下载 $downloaded 个作品")
                        if (skipped > 0) append("，查重跳过 $skipped 个")
                    }
                }
            }
        }

        val notification = NotificationCompat.Builder(this, DONE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(DONE_NOTIF_ID, notification)
        } catch (_: SecurityException) {
            // 未授予通知权限：忽略
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 pixdo 的运行与下载进度"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)

        val doneChannel = NotificationChannel(
            DONE_CHANNEL_ID,
            "任务完成",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "下载完成 / 停止 / 中断时提醒"
            setShowBadge(true)
        }
        mgr.createNotificationChannel(doneChannel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScrapeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("pixdo")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_download, "停止", stopIntent)

        if (RunState.downloading) {
            val target = RunState.target.coerceAtLeast(1)
            val done = RunState.downloaded.coerceIn(0, target)
            builder.setContentText("正在下载  $done / $target")
            builder.setProgress(100, done * 100 / target, false)
        } else {
            builder.setContentText(RunState.phase.ifEmpty { "正在搜索与筛选作品…" })
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
