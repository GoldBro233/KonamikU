package org.cf0x.konamiku.ui.viewmodels

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.nfc.NfcAdapter
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cf0x.konamiku.R
import org.cf0x.konamiku.data.AppDataStore
import org.cf0x.konamiku.nfc.EmulationController
import org.cf0x.konamiku.system.StatusDetector
import org.cf0x.konamiku.util.NfcRestart
import org.cf0x.konamiku.util.localized
import org.cf0x.konamiku.util.restartApp
import org.cf0x.konamiku.xposed.XposedActivationState
import org.cf0x.konamiku.xposed.XposedState
import java.util.concurrent.atomic.AtomicLong

/** Describes a pending user action awaiting confirmation via dialog. */
sealed class PendingAction {
    data object NfcEnable : PendingAction()
    data object NfcRestart : PendingAction()
    data object XposedRefresh : PendingAction()
}

class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        private const val TAG = "KonamikU-StatusVM"
    }

    private val context: Context get() = getApplication<Application>().applicationContext
    private val dataStore = AppDataStore(context)

    private val _status       = MutableStateFlow<StatusDetector.AllStatus?>(null)
    val status: StateFlow<StatusDetector.AllStatus?> = _status.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val _pendingAction = MutableStateFlow<PendingAction?>(null)
    val pendingAction: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

    private val _pmmEnabled = MutableStateFlow(true)
    val pmmEnabled: StateFlow<Boolean> = _pmmEnabled.asStateFlow()

    private val _showRestartDialog = MutableStateFlow(false)
    val showRestartDialog: StateFlow<Boolean> = _showRestartDialog.asStateFlow()

    /**
     * Bumped by every write to the NFC part of [_status]. A background probe
     * publishes only if no newer write happened while it ran, so a slow
     * result can never overwrite a fresher one.
     */
    private val nfcVersion = AtomicLong()

    init {
        refresh()
        observeXposedState()
        viewModelScope.launch {
            dataStore.pmmEnabled.collect { _pmmEnabled.value = it }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val version = nfcVersion.incrementAndGet()
            val detected = StatusDetector.detectAll(context)
            _status.update { current ->
                // detectAll carries its own XposedStatus snapshot; the
                // observed flows are newer, so keep theirs when present.
                val nfc = if (nfcVersion.get() == version) detected.nfc else current?.nfc ?: detected.nfc
                detected.copy(nfc = nfc, xposed = current?.xposed ?: detected.xposed)
            }
            _isRefreshing.value = false
        }
    }

    fun refreshNfc() {
        viewModelScope.launch {
            val version = nfcVersion.incrementAndGet()
            // NfcAdapter lookups are binder calls; never on the main thread.
            val nfc = withContext(Dispatchers.IO) { StatusDetector.detectNfc(context) }
            if (nfcVersion.get() == version) _status.update { it?.copy(nfc = nfc) }
        }
    }

    fun refreshXposed() {
        viewModelScope.launch {
            val xposed = withContext(Dispatchers.IO) { StatusDetector.detectXposed() }
            _status.update { it?.copy(xposed = xposed) }
        }
    }

    // ── Long-press triggers: show confirmation dialog ──

    fun onNfcLongPress() {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        if (adapter?.isEnabled == true) {
            viewModelScope.launch { _toastEvent.emit(str(R.string.toast_nfc_already_on)) }
            refreshNfc()
            return
        }
        _pendingAction.value = PendingAction.NfcEnable
    }

    fun onRootLongPress() {
        if (_status.value?.root?.available != true) {
            viewModelScope.launch { _toastEvent.emit(str(R.string.toast_root_no_permission)) }
            return
        }
        _pendingAction.value = PendingAction.NfcRestart
    }

    fun onXposedLongPress() {
        _pendingAction.value = PendingAction.XposedRefresh
    }

    // ── Dialog callbacks ──

    fun confirmAction() {
        val action = _pendingAction.value ?: return
        _pendingAction.value = null
        when (action) {
            PendingAction.NfcEnable     -> executeNfcEnable()
            PendingAction.NfcRestart    -> executeNfcRestart()
            // Read-only: looking at the status must not end the session.
            PendingAction.XposedRefresh -> executeXposedRefresh()
        }
    }

    fun cancelAction() {
        _pendingAction.value = null
    }

    /** Dismiss the restart-app dialog. */
    fun dismissRestartDialog() {
        _showRestartDialog.value = false
    }

    /**
     * Restart the entire app process. The mechanics live in
     * [org.cf0x.konamiku.util.restartApp] because the settings screen reaches
     * the same situation through its own NFC-restart flow.
     */
    fun restartApp() {
        _showRestartDialog.value = false
        context.restartApp()
    }

    // ── Execution methods ──

    private fun executeNfcEnable() {
        viewModelScope.launch(Dispatchers.IO) {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            if (adapter?.isEnabled == true) {
                _toastEvent.emit(str(R.string.toast_nfc_already_on))
                refreshNfc()
                return@launch
            }
            _toastEvent.emit(str(R.string.toast_nfc_enabling))
            runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "svc nfc enable")).waitFor()
            }
            refreshNfc()
            val enabled = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true
            _toastEvent.emit(
                if (enabled) str(R.string.toast_nfc_enable_success)
                else         str(R.string.toast_nfc_enable_fail)
            )
        }
    }

    private fun executeNfcRestart() {
        viewModelScope.launch {
            // The session ends before the service goes away, not alongside it.
            EmulationController.deactivate(context)
            _toastEvent.emit(str(R.string.toast_nfc_restarting))
            val result = NfcRestart.restart(context)
            NfcRestart.messages(context.localized(), result).forEach { _toastEvent.emit(it) }
            refreshNfc()
            runCatching {
                android.service.quicksettings.TileService.requestListeningState(
                    context,
                    ComponentName(context, org.cf0x.konamiku.system.KonamikuTileService::class.java)
                )
            }

            // Only ask for an app restart when the proxy probe failed. Dropping
            // the binder caches is normally enough to rebind in-process, and
            // prompting unconditionally was the reason every NFC restart ended
            // with a restart prompt.
            if (result.before != NfcRestart.Lookup.Unavailable && !result.proxyReachable) {
                Log.w(TAG, "HCE-F proxy unreachable after restart ($result) — asking for app restart")
                _showRestartDialog.value = true
            }
        }
    }

    private fun executeXposedRefresh() {
        viewModelScope.launch {
            _toastEvent.emit(str(R.string.toast_xposed_refreshing))
            refreshXposed()
        }
    }

    // ── Xposed state observation ──

    private fun observeXposedState() {
        viewModelScope.launch {
            combine(
                XposedState.activationStateFlow,
                XposedState.pmmActiveFlow,
                XposedState.frameworkNameFlow,
                XposedState.frameworkVersionFlow,
                XposedState.pmmStateFlow
            ) { state, pmm, name, version, pmmState ->
                StatusDetector.XposedStatus(
                    active       = state == XposedActivationState.ACTIVE,
                    needsRestart = state == XposedActivationState.NEEDS_RESTART,
                    provider     = "$name $version".trim(),
                    pmmActive    = pmm,
                    pmmState     = pmmState
                )
            }.collect { xposedStatus ->
                if (_status.value != null) {
                    _status.update { it?.copy(xposed = xposedStatus) }
                } else {
                    // First emission, before refresh() finished: a provisional
                    // snapshot, probed off the main thread. If refresh() won
                    // the race, only the Xposed part is applied to its result.
                    val nfc = withContext(Dispatchers.IO) { StatusDetector.detectNfc(context) }
                    _status.update { latest ->
                        latest?.copy(xposed = xposedStatus)
                            ?: StatusDetector.AllStatus(nfc = nfc, xposed = xposedStatus)
                    }
                }
            }
        }
    }

    private fun str(@StringRes resId: Int, vararg formatArgs: Any): String =
        context.localized().getString(resId, *formatArgs)
}
