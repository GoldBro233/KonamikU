package org.cf0x.konamiku.util

import android.content.Context
import android.nfc.NfcAdapter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.cf0x.konamiku.R
import java.util.concurrent.TimeUnit

object NfcRestart {

    private const val TAG = "KonamikU-NfcRestart"
    private const val PROCESS = "com.android.nfc"
    private const val ROOT_TIMEOUT_S = 10L

    /** What a by-name lookup of [PROCESS] found. */
    sealed interface Lookup {
        /** No root shell, or the lookup itself failed. */
        data object Unavailable : Lookup
        data object None : Lookup
        data class Single(val pid: Int) : Lookup
        /** Never signalled: there is no telling which one is the service. */
        data class Multiple(val pids: List<Int>) : Lookup
    }

    enum class Signal {
        /** No unique target, so nothing was signalled. */
        NOT_SENT,
        /** The target still was [PROCESS] and kill succeeded: termination requested. */
        SENT,
        /** The pid no longer belonged to [PROCESS] by the time it was checked. */
        TARGET_CHANGED,
        FAILED
    }

    /**
     * What was observed, step by step. "Termination requested", "a new
     * process replaced the old one" and "the service is usable" are separate
     * facts; callers report only what is true here.
     */
    data class Result(
        val before: Lookup,
        val signal: Signal,
        val after: Lookup,
        val nfcEnabled: Boolean,
        /**
         * Whether the HCE-F proxy answered a probe after the caches were
         * dropped. False means this process still cannot reach the service,
         * the one case that genuinely needs an app restart.
         */
        val proxyReachable: Boolean
    ) {
        val oldPid: Int? get() = (before as? Lookup.Single)?.pid
        val newPid: Int? get() = (after as? Lookup.Single)?.pid

        /** The signalled process is gone and exactly one other instance runs. */
        val processReplaced: Boolean
            get() = signal == Signal.SENT && oldPid != null && newPid != null && newPid != oldPid
    }

    /** Looks [PROCESS] up by exact process name via root. */
    fun lookup(): Lookup {
        // The marker separates "pidof found nothing" (exit 1) from "su failed".
        val out = runRoot("pidof $PROCESS; echo \"rc=\$?\"") ?: return Lookup.Unavailable
        val lines = out.lines()
        lines.firstOrNull { it.startsWith("rc=") } ?: return Lookup.Unavailable
        val pids = lines.filterNot { it.startsWith("rc=") }
            .flatMap { it.trim().split(Regex("\\s+")) }
            .mapNotNull { it.toIntOrNull() }
            .distinct()
        return when (pids.size) {
            0 -> Lookup.None
            1 -> Lookup.Single(pids[0])
            else -> Lookup.Multiple(pids)
        }
    }

    /**
     * Restarts the NFC service: signal the unique [PROCESS], toggle NFC,
     * drop this process's binder caches, then observe what actually happened.
     *
     * @param context Application context for NfcAdapter lookup.
     */
    suspend fun restart(context: Context): Result = withContext(Dispatchers.IO) {
        val before = lookup()
        Log.i(TAG, "Before restart: $before")
        if (before == Lookup.Unavailable) {
            return@withContext Result(before, Signal.NOT_SENT, before, isNfcEnabled(context), false)
        }

        // Signal only a single, re-verified target: a guessed pid can belong
        // to the shell that ran the lookup, or to whatever reused the number.
        val signal = (before as? Lookup.Single)?.let { signalIfStillTarget(it.pid) } ?: Signal.NOT_SENT
        Log.i(TAG, "Signal: $signal")
        delay(500)

        runRoot("svc nfc disable")
        delay(500)
        runRoot("svc nfc enable")
        delay(500)

        // The wait below needs a working adapter, and every card-emulation
        // call goes through the static proxies dropped here.
        clearNfcFCache()

        var enabled = isNfcEnabled(context)
        var waits = 0
        while (!enabled && waits++ < 15) {
            delay(500)
            enabled = isNfcEnabled(context)
        }

        // Rebinding is lazy; one retry covers a first call that lands while
        // the rebind is still in flight.
        val proxyOk = canReachNfcFService(context) || canReachNfcFService(context)
        val after = lookup()
        Result(before, signal, after, enabled, proxyOk).also { Log.i(TAG, "Restart result: $it") }
    }

    /**
     * Toasts describing [result], derived from observations only: a sent
     * signal is not reported as a restart unless a new process was seen.
     */
    fun messages(context: Context, result: Result): List<String> = buildList {
        when (val before = result.before) {
            Lookup.Unavailable -> add(context.getString(R.string.toast_root_no_permission))
            Lookup.None -> add(context.getString(R.string.toast_nfc_was_dead))
            is Lookup.Multiple ->
                add(context.getString(R.string.toast_nfc_restart_ambiguous, before.pids.joinToString()))
            is Lookup.Single -> add(
                when {
                    result.processReplaced -> context.getString(
                        R.string.toast_nfc_restarted, before.pid.toString(), result.newPid.toString()
                    )
                    result.signal == Signal.SENT ->
                        context.getString(R.string.toast_nfc_restart_unconfirmed, before.pid.toString())
                    else -> context.getString(R.string.toast_nfc_restart_failed, before.pid.toString())
                }
            )
        }
        if (result.nfcEnabled && result.proxyReachable) {
            add(context.getString(R.string.toast_nfc_service_refreshed))
        }
    }

    private fun signalIfStillTarget(pid: Int): Signal {
        // App processes carry their name in argv[0] with the rest zeroed, so
        // the substitution (which drops NULs) equals the bare name only for
        // the real service.
        val script = "c=\$(cat /proc/$pid/cmdline 2>/dev/null); " +
            "if [ \"\$c\" != \"$PROCESS\" ]; then echo changed; " +
            "elif kill $pid; then echo sent; else echo failed; fi"
        val out = runRoot(script) ?: return Signal.FAILED
        return when (out.trim().lines().lastOrNull()?.trim()) {
            "sent" -> Signal.SENT
            "changed" -> Signal.TARGET_CHANGED
            else -> Signal.FAILED
        }
    }

    /** Runs [command] as root; stdout when it exited 0, else null. */
    private fun runRoot(command: String): String? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        try {
            proc.outputStream.close()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(ROOT_TIMEOUT_S, TimeUnit.SECONDS)) return@runCatching null
            out.takeIf { proc.exitValue() == 0 }
        } finally {
            proc.destroy()
        }
    }.onFailure { Log.w(TAG, "Root command failed: ${it.message}") }.getOrNull()

    private fun isNfcEnabled(context: Context): Boolean =
        runCatching { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true }.getOrDefault(false)

    /**
     * Drops the binder caches that survive a NFC process restart.
     *
     * After `pkill com.android.nfc` the framework rebinds its own proxies, but
     * three things in *our* process still point at the dead binder:
     *
     *   NfcFCardEmulation.sCardEmus  HashMap<Context, NfcFCardEmulation>
     *   CardEmulation.sCardEmus      same shape
     *   *.sService                   the static INfc*CardEmulation proxy
     *
     * getInstance() only constructs a new manager when sCardEmus has no entry
     * for the context, so a cached manager survives forever - it keeps handing
     * back an object whose calls go through a dead sService. Clearing the map
     * alone is not enough either: sService is static and shared, so it has to
     * be nulled for the next call to take the recoverService() path and rebind.
     *
     * The field names are verified against AOSP: sCardEmus/sService have been
     * present since Android 13 with this exact shape, and they are still there
     * on Android 17 (checked against framework-nfc.jar from the device). The
     * previous implementation cleared sServiceCache/sAdapter, which do not
     * exist on any recent release - every attempt logged "No field" and the
     * caches were never actually dropped, which is why a full app restart
     * appeared to be required.
     *
     * @return true when at least one cache was dropped.
     */
    fun clearNfcFCache(): Boolean {
        Log.i(TAG, "Clearing NFC binder caches…")
        var cleared = false

        fun clearMap(clsName: String, fieldName: String) {
            runCatching {
                val field = Class.forName(clsName).getDeclaredField(fieldName)
                field.isAccessible = true
                val map = field.get(null) as? MutableMap<*, *> ?: return@runCatching
                val size = map.size
                map.clear()
                Log.i(TAG, "  $clsName.$fieldName cleared ($size entries)")
            }.onSuccess { cleared = true }
                .onFailure { Log.w(TAG, "  clearMap $clsName.$fieldName: ${it.message}") }
        }

        fun clearStatic(clsName: String, fieldName: String) {
            runCatching {
                val field = Class.forName(clsName).getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(null, null)
                Log.i(TAG, "  $clsName.$fieldName nulled")
            }.onSuccess { cleared = true }
                .onFailure { Log.w(TAG, "  clearStatic $clsName.$fieldName: ${it.message}") }
        }

        clearMap("android.nfc.cardemulation.NfcFCardEmulation", "sCardEmus")
        clearMap("android.nfc.cardemulation.CardEmulation", "sCardEmus")

        // The static proxies: nulling them is what forces a rebind, because
        // every API call dereferences them and recoverService() repopulates
        // them on the resulting RemoteException.
        clearStatic("android.nfc.cardemulation.NfcFCardEmulation", "sService")
        clearStatic("android.nfc.cardemulation.CardEmulation", "sService")
        clearStatic("android.nfc.NfcAdapter", "sNfcFCardEmulationService")
        clearStatic("android.nfc.NfcAdapter", "sCardEmulationService")

        Log.i(TAG, "NFC binder caches cleared (cleared=$cleared)")
        return cleared
    }

    /**
     * Probes whether the HCE-F proxy is live again after [clearNfcFCache].
     *
     * Rebinding is lazy - it happens on the first call that touches the proxy -
     * so a successful clear is not by itself proof that the app can talk to the
     * restored NFC service. This issues one cheap call and reports whether it
     * came back without throwing.
     */
    fun canReachNfcFService(context: Context): Boolean = runCatching {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        val emulation = android.nfc.cardemulation.NfcFCardEmulation.getInstance(adapter)
            ?: return false
        // getMaxNumOfRegisterableSystemCodes() is a plain binder round-trip and
        // has no side effects; -1 means the framework reported a failure.
        // It is @hide, so it is not visible to the compiler.
        org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
            android.nfc.cardemulation.NfcFCardEmulation::class.java,
            emulation,
            "getMaxNumOfRegisterableSystemCodes"
        ) as Int != -1
    }.onFailure {
        Log.w(TAG, "HCE-F proxy still unreachable after cache clear: ${it.message}")
    }.getOrDefault(false)
}
