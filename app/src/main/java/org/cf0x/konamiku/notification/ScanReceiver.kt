package org.cf0x.konamiku.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import org.cf0x.konamiku.R
import org.cf0x.konamiku.nfc.EmulationController
import org.cf0x.konamiku.util.localized

/**
 * Sent by [org.cf0x.konamiku.nfc.EmuCard] after it answered a read for a
 * session. Feedback is given only if that session is still the current one.
 */
class ScanReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SCAN = "org.cf0x.konamiku.ACTION_SCAN"
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCAN) return
        val session = EmulationController.session.value ?: return
        if (session.id != intent.getLongExtra(EXTRA_SESSION_ID, -1L)) return

        Toast.makeText(
            context,
            context.localized().getString(R.string.toast_card_scanned, session.card.name),
            Toast.LENGTH_SHORT
        ).show()
        runCatching {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (session.notify) LiveUpdateManager.pulse(context, session)
    }
}
