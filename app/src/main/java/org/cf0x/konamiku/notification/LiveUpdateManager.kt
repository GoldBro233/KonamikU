package org.cf0x.konamiku.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cf0x.konamiku.MainActivity
import org.cf0x.konamiku.R
import org.cf0x.konamiku.data.EmuMode
import org.cf0x.konamiku.nfc.EmulationController
import org.cf0x.konamiku.util.localized
import java.lang.reflect.Method

/**
 * The "card active" notification. It is only ever posted for a published
 * [EmulationController.Session] that runs HCE, and its actions name that
 * session, so an action from an older notification cannot stop a newer one.
 */
object LiveUpdateManager {

    private const val CHANNEL_ID_LIVE       = "konamiku_live"
    private const val CHANNEL_ID_IMPORTANT  = "konamiku_live_important"
    const val NOTIF_ID               = 1001
    const val ACTION_STOP            = "org.cf0x.konamiku.ACTION_STOP_EMULATION"
    const val ACTION_DISMISSED       = "org.cf0x.konamiku.ACTION_NOTIF_DISMISSED"
    const val EXTRA_SESSION_ID       = "session_id"

    private const val PULSE_MS = 2000L

    /** Outlives any receiver that asks for a pulse; goAsync() would not. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var pulseJob: Job? = null
    @Volatile
    private var promotedOngoingMethod: Method? = null
    @Volatile
    private var lastNotifyKey: Int = -1

    fun createChannel(base: Context) {
        val context = base.localized()
        val nm = context.getSystemService(NotificationManager::class.java)
        val liveChannel = NotificationChannel(
            CHANNEL_ID_LIVE,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val importantChannel = NotificationChannel(
            CHANNEL_ID_IMPORTANT,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(liveChannel)
        nm.createNotificationChannel(importantChannel)
    }

    fun postActive(base: Context, session: EmulationController.Session) {
        val context = base.localized()
        val (progress, modeTextRes) = when (session.mode) {
            EmuMode.NORMAL -> 20 to R.string.mode_normal
            EmuMode.COMPAT -> 25 to R.string.mode_compat
            EmuMode.NATIVE -> 30 to R.string.mode_native
        }
        notify(context, session.id, session.card.name, context.getString(modeTextRes), progress)
    }

    /** Briefly shows "scanned", then restores the active state if [session] is still current. */
    fun pulse(context: Context, session: EmulationController.Session) {
        val app = context.applicationContext.localized()
        notify(app, session.id, session.card.name, app.getString(R.string.notif_progress_simulated), 100)
        pulseJob?.cancel()
        pulseJob = scope.launch {
            delay(PULSE_MS)
            val current = EmulationController.session.value
            if (current?.id == session.id && current.notify) postActive(app, current)
        }
    }

    fun cancel(context: Context) {
        pulseJob?.cancel()
        lastNotifyKey = -1
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /**
     * Each session gets its own PendingIntents: the session id is part of the
     * intent data, which makes them distinct rather than one shared intent
     * whose extras are overwritten by the next activation.
     */
    private fun sessionAction(context: Context, action: String, sessionId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("konamiku://session/$sessionId"))
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** [context] must already be [localized]; the action label is resolved here. */
    private fun notify(context: Context, sessionId: Long, title: String, contentText: String, progress: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val isA15Plus = Build.VERSION.SDK_INT >= 35
        val channelId = if (isA15Plus) CHANNEL_ID_LIVE else CHANNEL_ID_IMPORTANT

        val notifyKey = listOf(sessionId, channelId, title, contentText, progress).hashCode()
        if (lastNotifyKey == notifyKey) return
        lastNotifyKey = notifyKey

        val tapIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = sessionAction(context, ACTION_STOP, sessionId)
        val delIntent  = sessionAction(context, ACTION_DISMISSED, sessionId)

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_nfc))
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(tapIntent)
            .setDeleteIntent(delIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, progress, false)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), context.getString(R.string.notif_action_stop), stopIntent).build())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        
        if (isA15Plus) {
            runCatching {
                val m = promotedOngoingMethod
                    ?: runCatching {
                        Notification.Builder::class.java
                            .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    }.onFailure {
                        android.util.Log.w("KonamikU-Live", "setRequestPromotedOngoing not found: ${it.message}")
                    }.getOrNull()
                if (m != null) {
                    promotedOngoingMethod = m
                    m.invoke(builder, true)
                }
            }.onFailure {
                android.util.Log.w("KonamikU-Live", "setRequestPromotedOngoing invoke failed: ${it.message}")
            }
        }

        runCatching { nm.notify(NOTIF_ID, builder.build()) }
            .onFailure { android.util.Log.w("KonamikU-Live", "Cannot post notification: ${it.message}") }
    }
}
