package org.cf0x.konamiku.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.cf0x.konamiku.MainActivity
import org.cf0x.konamiku.R
import org.cf0x.konamiku.util.localized

/**
 * State of the single in-app update: download → install session → system result.
 *
 * [UpdateDownloadService] owns the transfer, [UpdateInstaller] the session and
 * its persisted record, and [UpdateInstallReceiver] the system's verdict. All
 * of them publish here so the UI renders one task regardless of which page is
 * open or which of them is still alive.
 */
object UpdateTask {

    private const val TAG = "KonamikU-UpdateTask"

    /** Transfer progress. Low importance: it is ambient, not an alert. */
    const val CHANNEL_DOWNLOAD = "konamiku_update_download"

    /** "Update available", "confirm install" and failures: the user must see these. */
    const val CHANNEL_ALERT = "konamiku_update_alert"

    const val NOTIF_ID_PROGRESS = 2002
    private const val NOTIF_ID_OUTCOME = 2003

    enum class Phase {
        IDLE,
        DOWNLOADING,
        /** Copying into a session, or committed and waiting for the system. */
        INSTALLING,
        /** The system needs the user to confirm; [State.confirmIntent] opens it. */
        AWAITING_USER,
        FAILED
    }

    enum class FailureKind(@param:StringRes val messageRes: Int) {
        TIMEOUT(R.string.update_err_timeout),
        NOT_FOUND(R.string.setting_update_err_not_found),
        NETWORK(R.string.update_err_download),
        HTTP(R.string.update_err_download),
        PERMISSION(R.string.update_err_permission),
        INSTALL(R.string.update_err_install),
        /** The user declined the system confirmation. */
        ABORTED(R.string.update_err_aborted),
        /** The session outlived the process that could still drive it. */
        INTERRUPTED(R.string.update_err_interrupted)
    }

    /** What the user asked to download; kept so "retry" survives process death. */
    data class Request(val url: String, val fallbackUrl: String, val version: String)

    data class State(
        val taskId: Long = 0L,
        val phase: Phase = Phase.IDLE,
        /** 0..100, or -1 while the size is unknown. */
        val percent: Int = -1,
        /** The mirror failed and the transfer moved to the direct URL. */
        val retryingDirect: Boolean = false,
        /**
         * False once a session has been committed: the download is over, and
         * only the system can still decide the outcome.
         */
        val cancellable: Boolean = true,
        val kind: FailureKind? = null,
        /** Detail reported by PackageInstaller, if any. */
        val detail: String? = null,
        /** URL attempted last, for "open in browser". */
        val sourceUrl: String? = null,
        val request: Request? = null,
        /** Session handed to the system, once committed. */
        val sessionId: Int? = null,
        val confirmIntent: Intent? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * True while an Activity is resumed. The in-app dialog is then the
     * surface for outcomes; notifications are only for when the user is away.
     */
    @Volatile
    var uiVisible = false

    /** Session whose waiting dialog the user hid; [UpdateInstaller.reconcile] leaves it hidden. */
    @Volatile
    internal var hiddenSessionId: Int? = null

    internal fun set(state: State) {
        _state.value = state
    }

    /** Applies [transform] only while [taskId] still owns the state. */
    internal fun updateIfOwner(taskId: Long, transform: (State) -> State) {
        _state.update { if (it.taskId == taskId) transform(it) else it }
    }

    internal fun resetIfOwner(taskId: Long) {
        _state.update { if (it.taskId == taskId) State() else it }
    }

    /** Hides a committed install's dialog; the result still arrives through the receiver. */
    fun hide() {
        _state.update {
            if (it.phase == Phase.INSTALLING && !it.cancellable) {
                hiddenSessionId = it.sessionId
                State()
            } else {
                it
            }
        }
    }

    /**
     * The user gave up on a failed or unconfirmed install: forget it, so it is
     * neither shown again nor reconciled on the next launch.
     */
    fun discard(context: Context) {
        UpdateInstaller.discardRecord(context)
        cancelOutcomeNotification(context)
        _state.update {
            if (it.phase == Phase.FAILED || it.phase == Phase.AWAITING_USER) State() else it
        }
    }

    fun ensureChannels(base: Context) {
        val context = base.localized()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // createNotificationChannel on an existing channel only refreshes the
        // name and description, so a language change reaches users who already
        // have the channel, while their importance choice is kept.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                context.getString(R.string.notif_channel_update_download),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.notif_channel_update),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    /** Tells a user who is not looking that the update stopped. */
    internal fun notifyFailure(base: Context, kind: FailureKind, detail: String?) {
        if (uiVisible) return
        val context = base.localized()
        val text = detail?.takeIf { kind == FailureKind.INSTALL }
            ?: context.getString(kind.messageRes)
        postOutcome(context, context.getString(R.string.update_install_failed), text, openApp(context))
    }

    /** Lets a user who is not looking reach the system confirmation. */
    internal fun notifyConfirm(base: Context, sessionId: Int, confirm: Intent, version: String) {
        val context = base.localized()
        val tap = PendingIntent.getActivity(
            context,
            sessionId,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        postOutcome(
            context,
            context.getString(R.string.update_confirm_title),
            context.getString(R.string.update_confirm_desc, version),
            tap
        )
    }

    internal fun cancelOutcomeNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_OUTCOME)
    }

    private fun postOutcome(context: Context, title: String, text: String, tap: PendingIntent) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_OUTCOME, notification) }
            .onFailure { Log.w(TAG, "Cannot post update notification: ${it.message}") }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
