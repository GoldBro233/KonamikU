package org.cf0x.konamiku.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cf0x.konamiku.nfc.EmulationController

/**
 * Stop and swipe-away on the "card active" notification. Both name the
 * session that posted the notification, so neither can end a newer one.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveUpdateManager.ACTION_STOP &&
            intent.action != LiveUpdateManager.ACTION_DISMISSED
        ) return
        val sessionId = intent.getLongExtra(LiveUpdateManager.EXTRA_SESSION_ID, -1L)
        val pending = goAsync()
        scope.launch {
            try {
                EmulationController.deactivateIfCurrent(context, sessionId)
            } finally {
                pending.finish()
            }
        }
    }
}
