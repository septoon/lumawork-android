package ru.lumastack.lumawork.data.ftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import ru.lumastack.lumawork.LumaWorkApplication
import ru.lumastack.lumawork.MainActivity

/** User-started archive transfers keep their own long HTTP timeout while the screen is closed. */
class FtpArchiveDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container get() = (application as LumaWorkApplication).container
    private var task: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate(); active.set(true)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки FTP", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("Подготовка архива FTP", null))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ftp-archive").also {
            it.acquire(TimeUnit.HOURS.toMillis(6))
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (task?.isActive == true) return START_STICKY
        task = scope.launch {
            try {
                while (isActive) {
                    val record = container.ftpTransfers.pendingArchives().firstOrNull() ?: break
                    val session = container.authRepository.session?.takeIf { it.user.id == record.ownerId }
                    if (!container.ftpTransferGateway.archives.progress(record).state.isActive) Unit
                    else if (session == null) container.ftpTransferGateway.archives.fail(record, "Войдите в аккаунт снова и повторите загрузку.")
                    else {
                        startForeground(NOTIFICATION, notification(record.fileName, null))
                        container.ftpTransferGateway.downloadArchive(record, session.token) { progress ->
                            startForeground(NOTIFICATION, notification(record.fileName, progress))
                        }
                    }
                    ensureActive()
                    container.ftpTransfers.refresh(record.ownerId)
                    FtpTransferJobService.schedule(this@FtpArchiveDownloadService)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    task = null
                    // A later start request must not be stopped by an older completion.
                    if (scope.isActive && lastStartId != startId) onStartCommand(null, 0, lastStartId)
                    else if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        return START_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        container.ftpTransferGateway.archives.interrupt()
        scope.cancel(); stopSelf()
    }
    override fun onDestroy() {
        active.set(false); container.ftpTransferGateway.archives.interrupt()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(name: String, progress: FtpTransferProgress?): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val total = progress?.total?.takeIf { it > 0 }
        val percent = if (total != null) (progress.bytes.toDouble() / total * 100).toInt().coerceIn(0, 100) else 0
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name).setContentText(if (progress == null) "Подготовка архива FTP" else "Загрузка архива FTP")
            .setContentIntent(intent).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent, total == null).build()
    }
    companion object {
        private val active = AtomicBoolean(false)
        private const val CHANNEL = "ftp-archives"
        private const val NOTIFICATION = 0x465451
        fun start(context: Context, force: Boolean = false) {
            if (force || !active.get()) ContextCompat.startForegroundService(context, Intent(context, FtpArchiveDownloadService::class.java))
        }
    }
}
