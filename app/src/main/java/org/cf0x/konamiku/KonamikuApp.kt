package org.cf0x.konamiku

import android.app.Application
import android.os.Build
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cf0x.konamiku.data.AppDataStore
import org.cf0x.konamiku.data.AppLocale
import org.cf0x.konamiku.system.UpdateManager
import org.cf0x.konamiku.util.applyLocale
import org.cf0x.konamiku.util.mirrorSystemLocale
import org.cf0x.konamiku.util.systemAppLocale
import org.cf0x.konamiku.xposed.KonamikuModule
import org.cf0x.konamiku.xposed.NfcHookReceiver
import org.cf0x.konamiku.xposed.XposedActivationState
import org.cf0x.konamiku.xposed.XposedState

class KonamikuApp : Application(), XposedServiceHelper.OnServiceListener {

    private companion object {
        private const val TAG = "KonamikU"
    }

    /**
     * Application-scoped worker for writes that must not block startup.
     *
     * Application.onCreate runs on the main thread before the first frame, so
     * anything awaited there is pure startup latency. Reads whose value is
     * needed immediately (the locale, used to configure resources before the
     * first screen) still block once, but every write is fire-and-forget.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val dataStore = AppDataStore(this)

        // A process restart is not a normal lifecycle event - it means the app
        // was killed, crashed, or updated. Runtime state does not survive that:
        // the Xposed activation snapshot is reset here and re-derived, and the
        // emulation session lives only in memory (EmulationController), so a
        // new process starts with no card routed rather than one that looks
        // active while nothing is registered.
        XposedState.reset()
        appScope.launch {
            val interval = dataStore.updateInterval.first()
            UpdateManager.schedule(this@KonamikuApp, interval)
        }

        syncLocale(dataStore)

        XposedServiceHelper.registerListener(this)
    }

    /**
     * Puts the language choice in effect for this process; see LocaleHelper
     * for which store is authoritative on which API level.
     */
    private fun syncLocale(dataStore: AppDataStore) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system already applies its per-app language to every
            // context, so nothing here blocks startup.
            appScope.launch {
                runCatching {
                    val stored = dataStore.appLocaleSnapshot()
                    if (!stored.migrated) {
                        // Older builds kept the choice only in DataStore. Hand
                        // it to the system once; after that an empty system
                        // list means "follow the system", not "restore ours".
                        if (systemAppLocale() == AppLocale.SYSTEM && stored.locale != AppLocale.SYSTEM) {
                            applyLocale(stored.locale.tag)
                        }
                        dataStore.markLocaleMigrated()
                    } else {
                        mirrorSystemLocale(dataStore)
                    }
                }.onFailure { Log.w(TAG, "Locale sync failed: ${it.message}") }
            }
        } else {
            // AppCompat holds the choice only in memory below 33, and it has
            // to be in place before the first Activity, so this read blocks.
            val stored = runCatching {
                runBlocking { dataStore.appLocaleSnapshot().locale }
            }.getOrDefault(AppLocale.SYSTEM)
            applyLocale(stored.tag)
        }
    }

    override fun onServiceBind(service: XposedService) {
        // Wrapped whole: this is a framework callback, and an exception thrown
        // out of it aborts the bind rather than just losing one status field.
        // Individual calls below are still guarded so a partial failure keeps
        // whatever did work.
        runCatching {
            XposedState.frameworkName    = service.frameworkName
            XposedState.frameworkVersion = service.frameworkVersion
        }.onFailure { Log.w(TAG, "Cannot read framework identity: ${it.message}") }

        runCatching {
            // Restore the last reported state so the panel is correct before
            // the next ping arrives. Older builds wrote only the boolean, so
            // both forms are honoured.
            val prefs = getSharedPreferences(NfcHookReceiver.PREFS_NAME, MODE_PRIVATE)
            XposedState.pmmState = when {
                prefs.contains("pmmtool_state") ->
                    KonamikuModule.PmmState.fromCode(prefs.getInt("pmmtool_state", 0))
                prefs.getBoolean("pmmtool_active", false) ->
                    KonamikuModule.PmmState.INSTALLED
                else -> KonamikuModule.PmmState.NOT_ATTEMPTED
            }
        }

        val hooked = runCatching<Boolean> {
            service.getRunningTargets().any { it.processName == "com.android.nfc" }
        }.getOrDefault(false)

        XposedState.activationState = if (hooked) XposedActivationState.ACTIVE
                                      else XposedActivationState.NEEDS_RESTART
        // Authoritative confirmation from the framework, not from a broadcast.
        // NfcHookReceiver only trusts its ping once this is set.
        if (hooked) {
            XposedState.markFrameworkConfirmed()
            XposedState.pendingReport?.let {
                Log.i(TAG, "Applying deferred hook ping: $it")
                NfcHookReceiver.applyPing(this, it)
                XposedState.pendingReport = null
            }
        }

        Log.i(TAG, "Bound: state=${XposedState.activationState} hooked=$hooked")
    }

    override fun onServiceDied(service: XposedService) {
        XposedState.reset()
    }
}
