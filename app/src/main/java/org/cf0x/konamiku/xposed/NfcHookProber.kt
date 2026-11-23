package org.cf0x.konamiku.xposed

import android.content.ComponentName
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation
import android.util.Log
import org.cf0x.konamiku.nfc.EmuCard
import org.cf0x.konamiku.nfc.EmulationController
import org.cf0x.konamiku.nfc.SYSTEM_CODE_FELICA

object NfcHookProber {
    private const val TAG = "KonamikU-Prober"

    /**
     * Probes if the NFC hook (isValidSystemCode) is active by attempting to register
     * a system code that would normally be rejected (or just calling the method).
     *
     * The probe goes through [EmuCard]'s own registration, so an HCE session
     * that is running gets its system code back afterwards.
     */
    fun probe(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        val emulation = runCatching { NfcFCardEmulation.getInstance(adapter) }.getOrNull()
            ?: return false
        val component = ComponentName(context, EmuCard::class.java)

        // Use a system code that Android would always reject without our hook.
        // "88B4" can succeed on stock Android (it's a normal valid code), giving a
        // false positive on non-hooked devices. "FFFF" is guaranteed to be rejected
        // by isValidSystemCode on stock ROMs, so it only succeeds when our hook
        // makes isValidSystemCode always return true.
        val hooked = runCatching {
            val ok = emulation.registerSystemCodeForService(component, "FFFF")
            if (ok) {
                runCatching {
                    emulation.unregisterSystemCodeForService(component)
                }.onFailure { e ->
                    Log.w(TAG, "Failed to unregister probe code: ${e.message}")
                }
            }
            ok
        }.getOrDefault(false)

        if (EmulationController.session.value?.hce == true) {
            runCatching { emulation.registerSystemCodeForService(component, SYSTEM_CODE_FELICA) }
                .onFailure { Log.w(TAG, "Cannot restore the active session's system code: ${it.message}") }
        }
        return hooked
    }
}
