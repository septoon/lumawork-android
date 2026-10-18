package ru.lumastack.lumawork.data.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.LumaWorkApplication
import ru.lumastack.lumawork.MainActivity
import ru.lumastack.lumawork.R

/** Shared by the settings screen, foreground observer and system job. */
class RequestNotificationsRuntime private constructor(context: Context) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository = NotificationsRepository(
        OkHttpNotificationsApi(BuildConfig.API_ORIGIN), SharedPreferencesNotificationsCache(this.context),
        { (this.context as LumaWorkApplication).container.authRepository.session },
    )
    val delivery = AndroidRequestNotificationDelivery(this.context)

    companion object {
        @Volatile private var instance: RequestNotificationsRuntime? = null
        fun get(context: Context): RequestNotificationsRuntime = instance ?: synchronized(this) {
            instance ?: RequestNotificationsRuntime(context).also { instance = it }
        }
    }
}

class AndroidRequestNotificationDelivery(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun isEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun openSettings() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @SuppressLint("MissingPermission") // isEnabled checks API 33 permission; notify also handles revocation.
    fun deliver(event: NotificationEvent): Boolean {
        if (!isEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Активные заявки", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Новые активные заявки и изменение исполнителя"
        })
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_REQUESTS)
            .putExtra(EXTRA_REQUEST_ID, event.requestID)
            // The data URI keeps PendingIntents for separate requests independent.
            .setData(android.net.Uri.Builder().scheme("lumawork").authority("requests").appendPath(event.requestID).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_nav_assignment)
            .setContentTitle(event.title).setContentText(event.body)
            .setStyle(Notification.BigTextStyle().bigText(event.body)).setContentIntent(pendingIntent)
            .setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        return try { manager.notify(event.eventID, 0, notification); true } catch (_: SecurityException) { false }
    }

    companion object {
        const val ACTION_OPEN_REQUESTS = "ru.lumastack.lumawork.OPEN_REQUESTS"
        const val EXTRA_REQUEST_ID = "requestID"
        private const val CHANNEL = "active_requests"
    }
}
