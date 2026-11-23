package org.cf0x.konamiku.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation
import android.os.DeadObjectException
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cf0x.konamiku.data.EmuMode
import org.cf0x.konamiku.data.NfcCard
import org.cf0x.konamiku.notification.LiveUpdateManager
import org.cf0x.konamiku.util.NfcRestart
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the one emulation session: which card is selected, in which effective
 * mode, and whether the platform actually routes HCE-F to us.
 *
 * Selecting a card is not the same as routing being enabled. A session is
 * published only after every platform step it needs has succeeded, and the
 * UI, the notification and [EmuCard] all read that same snapshot. Every
 * change first invalidates the previous session, so no packet is answered
 * with data the user has already switched away from.
 *
 * HCE-F is a foreground-only platform feature (NfcFCardEmulation.enableService
 * needs a resumed Activity, and the NFC service unregisters it when the app
 * leaves the foreground), so a session never outlives the host Activity's
 * resumed period, except across a configuration change, after which it is
 * re-established rather than assumed.
 */
object EmulationController {

    private const val TAG = "KonamikU-Emulation"
    private const val RETRY_DELAY_MS = 300L

    data class Session(
        val id: Long,
        val card: NfcCard,
        /**
         * Effective mode. [EmuMode.NATIVE] means the card is selected but the
         * app does not run HCE: nothing is registered and nothing answers.
         */
        val mode: EmuMode,
        /** Whether the "card active" notification is shown for this session. */
        val notify: Boolean
    ) {
        val hce: Boolean get() = mode != EmuMode.NATIVE
    }

    /** Card data [EmuCard] answers with, bound to the session that registered it. */
    class Responder(val sessionId: Long, val card: FelicaCard)

    enum class Failure { NO_ACTIVITY, NFC_UNAVAILABLE, REJECTED, ENABLE_FAILED, SUPERSEDED }

    sealed interface Outcome {
        data class Active(val session: Session) : Outcome
        data class Failed(val reason: Failure) : Outcome
    }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Read by [EmuCard] on every packet; null means "do not answer". */
    @Volatile
    var responder: Responder? = null
        private set

    @Volatile
    private var host: WeakReference<Activity>? = null

    /** Set when the host is recreated for a configuration change. */
    @Volatile
    private var reconfirmOnResume = false

    private val mutex = Mutex()
    private val sessionIds = AtomicLong()

    /**
     * Bumped by every activate and deactivate request. An activation that
     * finds a newer value at one of its checkpoints gives up, so a stop issued
     * while it waits wins.
     */
    private val requests = AtomicLong()

    // ── Host lifecycle (MainActivity) ────────────────────────────────────

    fun attachHost(activity: Activity) {
        host = WeakReference(activity)
    }

    /**
     * Called from the host's onPause. A real departure ends the session at
     * once: the software responder stops before anything else, and the route
     * is withdrawn while the platform still accepts the call from this
     * Activity (it clears the resumed flag only after onPause returns).
     * A configuration change keeps the selection and re-establishes it when
     * the new instance resumes, because the route cannot be assumed to survive.
     */
    fun onHostPaused(activity: Activity, changingConfigurations: Boolean) {
        if (changingConfigurations) {
            reconfirmOnResume = _session.value?.hce == true
        } else {
            requests.incrementAndGet()
            val previous = clear(activity)
            if (previous?.hce == true) disableRoute(activity, activity)
        }
        if (host?.get() === activity) host = null
    }

    /** Re-establishes a session kept across a configuration change. */
    suspend fun onHostResumed(context: Context) {
        if (!reconfirmOnResume) return
        reconfirmOnResume = false
        val kept = _session.value ?: return
        if (kept.hce) activate(context, kept.card, kept.mode, kept.notify)
    }

    // ── Session changes ──────────────────────────────────────────────────

    /**
     * Makes [card] the active card in effective [mode]. Returns once the
     * session is published, or with the reason it was not; on failure no
     * session remains, not even the previous one.
     */
    suspend fun activate(context: Context, card: NfcCard, mode: EmuMode, notify: Boolean): Outcome {
        val request = requests.incrementAndGet()
        val app = context.applicationContext
        return mutex.withLock {
            withContext(Dispatchers.Main.immediate) { activateLocked(app, card, mode, notify, request) }
        }
    }

    /** Ends the current session, waiting for any activation in flight to settle first. */
    suspend fun deactivate(context: Context) {
        requests.incrementAndGet()
        val app = context.applicationContext
        mutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                val previous = clear(app)
                if (previous?.hce == true) disableRoute(app, resumedHost())
            }
        }
    }

    /** Ends session [sessionId] if it is still the current one; stale actions are ignored. */
    suspend fun deactivateIfCurrent(context: Context, sessionId: Long) {
        if (_session.value?.id != sessionId) {
            Log.i(TAG, "Ignoring action for stale session $sessionId")
            return
        }
        deactivate(context)
    }

    private suspend fun activateLocked(
        context: Context,
        card: NfcCard,
        mode: EmuMode,
        notify: Boolean,
        request: Long
    ): Outcome {
        val previous = clear(context)
        if (previous?.hce == true) disableRoute(context, resumedHost())
        if (request != requests.get()) return Outcome.Failed(Failure.SUPERSEDED)

        val id = sessionIds.incrementAndGet()
        if (mode == EmuMode.NATIVE) {
            // HCE stays off: the card is selected, nothing is registered.
            val session = Session(id, card, mode, notify)
            _session.value = session
            Log.i(TAG, "Selected ${card.name} without HCE (session $id)")
            return Outcome.Active(session)
        }

        if (resumedHost() == null) return Outcome.Failed(Failure.NO_ACTIVITY)
        val emulation = nfcF(context) ?: return Outcome.Failed(Failure.NFC_UNAVAILABLE)
        val component = ComponentName(context, EmuCard::class.java)
        val activeIdm = resolveActiveIdm(card.idm, mode)
        val felica = withContext(Dispatchers.IO) {
            runCatching { FelicaCard(context, activeIdm = activeIdm, realIdm = card.idm.uppercase()) }
                .onFailure { Log.e(TAG, "Cannot build card data: ${it.message}") }
                .getOrNull()
        } ?: return Outcome.Failed(Failure.REJECTED)

        return try {
            var registered = register(emulation, component, activeIdm)
            if (!registered) {
                Log.w(TAG, "Registration rejected for $activeIdm ($mode), retrying once")
                delay(RETRY_DELAY_MS)
                if (request != requests.get()) return abandon(context, Failure.SUPERSEDED)
                registered = register(emulation, component, activeIdm)
            }
            if (!registered) return abandon(context, Failure.REJECTED)
            if (request != requests.get()) return abandon(context, Failure.SUPERSEDED)

            val activity = resumedHost() ?: return abandon(context, Failure.NO_ACTIVITY)
            val enabled = runCatching { emulation.enableService(activity, component) }
                .onFailure { Log.w(TAG, "enableService threw: ${it.message}") }
                .getOrDefault(false)
            if (!enabled) return abandon(context, Failure.ENABLE_FAILED)

            // No suspension between this check and the publish: a pause on
            // the main thread cannot slip in and be overwritten.
            if (request != requests.get()) return abandon(context, Failure.SUPERSEDED)
            val session = Session(id, card, mode, notify)
            responder = Responder(id, felica)
            _session.value = session
            if (notify) LiveUpdateManager.postActive(context, session)
            Log.i(TAG, "Activated ${card.name} [$activeIdm] in $mode (session $id)")
            Outcome.Active(session)
        } catch (e: Exception) {
            Log.e(TAG, "Activation failed: ${e.javaClass.simpleName}: ${e.message}")
            if (e is DeadObjectException || e.message?.contains("Failed to reach") == true) {
                NfcRestart.clearNfcFCache()
            }
            abandon(context, Failure.NFC_UNAVAILABLE)
        }
    }

    private fun register(emulation: NfcFCardEmulation, component: ComponentName, idm: String): Boolean {
        val idmOk = emulation.setNfcid2ForService(component, idm)
        val sysOk = emulation.registerSystemCodeForService(component, SYSTEM_CODE_FELICA)
        if (!idmOk || !sysOk) Log.w(TAG, "Registration result: nfcid2=$idmOk systemCode=$sysOk")
        return idmOk && sysOk
    }

    /** Withdraws whatever a failed activation set up; it never published a session. */
    private fun abandon(context: Context, reason: Failure): Outcome {
        disableRoute(context, resumedHost())
        Log.w(TAG, "Activation abandoned: $reason")
        return Outcome.Failed(reason)
    }

    /** Stops answering and hides the session. Returns the session that ended. */
    private fun clear(context: Context): Session? {
        val previous = _session.value
        responder = null
        _session.value = null
        LiveUpdateManager.cancel(context)
        return previous
    }

    /**
     * Withdraws the HCE-F route. Without a resumed Activity the platform
     * refuses the call; the software responder is already off by then, and
     * the NFC service drops the route itself once the app leaves the foreground.
     */
    private fun disableRoute(context: Context, activity: Activity?) {
        if (activity == null) {
            Log.i(TAG, "No resumed Activity; route is left to the platform")
            return
        }
        runCatching { nfcF(context)?.disableService(activity) }
            .onFailure { Log.w(TAG, "disableService failed: ${it.message}") }
    }

    private fun resumedHost(): Activity? {
        val activity = host?.get() ?: return null
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle ?: return null
        return activity.takeIf { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    }

    /** A fresh instance each time: the cached proxy dies with an NFC restart. */
    private fun nfcF(context: Context): NfcFCardEmulation? {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return null
        return runCatching { NfcFCardEmulation.getInstance(adapter) }.getOrNull()
    }
}
