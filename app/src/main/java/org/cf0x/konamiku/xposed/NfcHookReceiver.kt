package org.cf0x.konamiku.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the "hook is live" ping from the Xposed module.
 *
 * The sender is the module running inside `com.android.nfc`, which has a
 * different uid and signing certificate than us, so the manifest cannot
 * restrict who delivers this action. Instead of trying to authenticate the
 * sender (impossible without a shared secret), this receiver treats the ping
 * as *advisory*:
 *
 *  - it always records the pmms state that the module reported, and
 *  - it only promotes [XposedActivationState] to ACTIVE when the Xposed
 *    framework itself reports our module as loaded for com.android.nfc,
 *    which is authoritative and cannot be faked by a third-party app.
 */
class NfcHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NFC_HOOKED) return

        // Older module builds sent only the boolean; fall back to it when the
        // richer state is absent so an upgraded app still reads an older hook.
        val state = if (intent.hasExtra(EXTRA_PMM_STATE)) {
            KonamikuModule.PmmState.fromCode(intent.getIntExtra(EXTRA_PMM_STATE, 0))
        } else if (intent.getBooleanExtra("pmmtool_active", false)) {
            KonamikuModule.PmmState.INSTALLED
        } else {
            KonamikuModule.PmmState.NOT_ATTEMPTED
        }
        val report = Report(
            state = state,
            processStart = intent.getStringExtra(EXTRA_INSTANCE)
                ?.substringAfter(':', "")?.toLongOrNull(),
            seq = intent.getLongExtra(EXTRA_SEQ, 0L)
        )
        Log.i("KonamikU", "NFC hook ping — $report")

        // This action cannot be authenticated (the sender is com.android.nfc,
        // a different uid and certificate), so the ping is only honoured once
        // the Xposed framework has confirmed our module is loaded there.
        // Without that gate any app could broadcast this and flip pmmActive,
        // which is half of the condition that unlocks NORMAL/COMPAT mode.
        if (!XposedState.frameworkConfirmed) {
            Log.w("KonamikU", "Deferring hook ping until the framework confirms the module")
            if (XposedState.pendingReport?.let { report.isNewerThan(it) } != false) {
                XposedState.pendingReport = report
            }
            return
        }

        applyPing(context, report)
    }

    /**
     * One state report from an NFC process.
     *
     * @property processStart the reporting process's start time on the
     *           uptime clock, or null from module builds that predate it.
     * @property seq the report's order within that process.
     */
    data class Report(
        val state: KonamikuModule.PmmState,
        val processStart: Long?,
        val seq: Long
    ) {
        /**
         * Broadcasts are not ordered: a report from a process that has since
         * restarted, or an earlier report of the same process, must not
         * overwrite what a later one said. Unversioned reports are taken as is.
         */
        fun isNewerThan(other: Report): Boolean {
            if (processStart == null || other.processStart == null) return true
            if (processStart != other.processStart) return processStart > other.processStart
            return seq > other.seq
        }
    }

    companion object {
        const val ACTION_NFC_HOOKED = "org.cf0x.konamiku.ACTION_NFC_HOOKED"
        const val EXTRA_PMM_STATE = "pmmtool_state"
        const val EXTRA_INSTANCE = "nfc_instance"
        const val EXTRA_SEQ = "report_seq"
        const val PREFS_NAME = "KonamikU_xposed"

        /** Records a ping the framework has vouched for, unless a newer one was already applied. */
        fun applyPing(context: Context, report: Report) {
            if (!XposedState.applyReport(report)) {
                Log.i("KonamikU", "Ignoring stale hook ping $report")
                return
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("pmmtool_active", report.state == KonamikuModule.PmmState.INSTALLED)
                .putInt("pmmtool_state", report.state.code)
                .apply()
        }
    }
}
