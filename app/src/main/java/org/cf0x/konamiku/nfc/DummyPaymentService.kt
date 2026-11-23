package org.cf0x.konamiku.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Placeholder payment service so the app can be chosen in the default payment
 * role. The system binds it for APDUs; it is never started explicitly, so it
 * has no onStartCommand behaviour of its own.
 */
class DummyPaymentService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        return null
    }

    override fun onDeactivated(reason: Int) {
        android.util.Log.i("KonamikU", "DummyPaymentService: onDeactivated (reason: $reason)")
    }
}
