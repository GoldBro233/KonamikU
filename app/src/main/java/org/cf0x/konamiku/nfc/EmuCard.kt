package org.cf0x.konamiku.nfc

import android.content.Intent
import android.nfc.cardemulation.HostNfcFService
import android.os.Bundle
import android.util.Log
import org.cf0x.konamiku.notification.ScanReceiver

/**
 * HCE-F responder.
 *
 * [processNfcFPacket] runs on the app's main thread with a few hundred
 * milliseconds of budget, so it never touches disk: it answers from the
 * [EmulationController.Responder] that was built and published together with
 * the session, and answers nothing when there is none. Invalidating a session
 * clears the responder first, so a packet can never be answered with the data
 * of a card the user has switched away from.
 */
class EmuCard : HostNfcFService() {

    companion object {
        private const val TAG = "KonamikU-EmuCard"
        const val EXTRA_AUTO_ACTIVATE = "org.cf0x.konamiku.EXTRA_AUTO_ACTIVATE"

        private const val CMD_READ = 0x06
        private const val CMD_WRITE = 0x08
    }

    override fun processNfcFPacket(commandPacket: ByteArray, extras: Bundle?): ByteArray? {
        val responder = EmulationController.responder ?: return null

        // The length byte is unsigned: comparing ByteArray.size.toByte()
        // against it fails for any frame of 128+ bytes.
        val declared = commandPacket.getOrNull(0)?.toInt()?.and(0xFF) ?: return null
        if (commandPacket.size < 1 + 1 + 8 || commandPacket.size != declared) {
            Log.w(TAG, "Invalid packet length: size=${commandPacket.size} declared=$declared")
            return null
        }

        return when (commandPacket[1].toInt() and 0xFF) {
            CMD_READ -> handleRead(responder.card, commandPacket)?.also { response ->
                // Byte 10 is Status Flag1: only a successful read is a "scan".
                if (response[10].toInt() == 0) fireScanBroadcast(responder.sessionId)
            }
            CMD_WRITE -> handleWrite(responder.card)
            else -> null
        }
    }

    // ── FeliCa command handlers ──

    private fun handleRead(card: FelicaCard, cmd: ByteArray): ByteArray? =
        when (val request = FelicaRead.parse(cmd)) {
            FelicaRead.Request.Malformed -> {
                Log.w(TAG, "Malformed read command (${cmd.size} bytes)")
                null
            }
            is FelicaRead.Request.Rejected -> {
                Log.w(TAG, "Rejecting read: flag1=%02X flag2=%02X".format(request.flag1, request.flag2))
                readResponse(card, request.flag1, request.flag2, emptyList())
            }
            is FelicaRead.Request.Blocks ->
                readResponse(card, 0x00, 0x00, request.numbers.map { card.readBlock(it) })
        }

    /**
     * Read response: `len 07 IDm SF1 SF2 [n Block[16n]]`; the block count and
     * data are present only on success (Status Flag1 = 00h).
     */
    private fun readResponse(card: FelicaCard, flag1: Int, flag2: Int, blocks: List<ByteArray>): ByteArray {
        val success = flag1 == 0
        val length = 1 + 1 + 8 + 2 + if (success) 1 + blocks.size * 16 else 0
        return ByteArray(length).also { r ->
            var idx = 0
            r[idx++] = length.toByte()
            r[idx++] = 0x07
            card.activeIdmBytes.copyInto(r, idx); idx += 8
            r[idx++] = flag1.toByte()
            r[idx++] = flag2.toByte()
            if (success) {
                r[idx++] = blocks.size.toByte()
                for (b in blocks) { b.copyInto(r, idx); idx += 16 }
            }
        }
    }

    private fun handleWrite(card: FelicaCard): ByteArray {
        val resp = ByteArray(1 + 1 + 8 + 2)  // len + 0x09 + IDm + 2 status flags
        resp[0] = resp.size.toByte()
        resp[1] = 0x09
        card.activeIdmBytes.copyInto(resp, 2)
        return resp
    }

    private fun fireScanBroadcast(sessionId: Long) {
        runCatching {
            sendBroadcast(
                Intent(ScanReceiver.ACTION_SCAN)
                    .setPackage(packageName)
                    .putExtra(ScanReceiver.EXTRA_SESSION_ID, sessionId)
            )
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "onDeactivated reason=$reason")
    }
}
