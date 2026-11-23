package org.cf0x.konamiku.nfc

/**
 * Parsing of the FeliCa Read Without Encryption command (code 06h).
 *
 * Frame: `len 06 IDm[8] n ServiceCode[2n] m BlockListElement…`, with each
 * Block List Element laid out per the Sony FeliCa Card User's Manual
 * (Excerpted Edition 2.3) §4.2.1:
 *
 * - D0 b7: 1 = 2-byte element, 0 = 3-byte element
 * - D0 b6-b4: Access Mode
 * - D0 b3-b0: Service Code List Order (index into the n service codes)
 * - then the Block Number: 1 byte (2-byte element) or 2 bytes little-endian
 *   (3-byte element)
 *
 * D0 never carries block number bits.
 */
object FelicaRead {

    /** Status Flag1 for an error that is not tied to one list position. */
    const val FLAG1_GENERAL = 0xFF

    /** Status Flag2, Table 4-12: Number of Service out of range. */
    const val FLAG2_ILLEGAL_SERVICE_COUNT = 0xA1

    /** Status Flag2, Table 4-12: Number of Block out of range. */
    const val FLAG2_ILLEGAL_BLOCK_COUNT = 0xA2

    /** Status Flag2, Table 4-12: Service Code List Order beyond Number of Service. */
    const val FLAG2_ILLEGAL_SERVICE_ORDER = 0xA3

    private const val MAX_SERVICES = 16

    /**
     * Largest block count whose response still fits the one-byte length:
     * 13 header bytes + 16 per block ≤ 255.
     */
    const val MAX_BLOCKS = 15

    private const val HEADER = 1 + 1 + 8

    sealed interface Request {
        /** A valid read of these blocks, in request order. */
        data class Blocks(val numbers: List<Int>) : Request

        /** A well-formed command the card rejects with these status flags. */
        data class Rejected(val flag1: Int, val flag2: Int) : Request

        /** Truncated or padded: not a frame the card can answer at all. */
        data object Malformed : Request
    }

    fun parse(cmd: ByteArray): Request {
        var pos = HEADER
        if (cmd.size <= pos) return Request.Malformed
        val serviceCount = cmd[pos++].u8()
        if (serviceCount !in 1..MAX_SERVICES) {
            return Request.Rejected(FLAG1_GENERAL, FLAG2_ILLEGAL_SERVICE_COUNT)
        }
        pos += serviceCount * 2
        if (cmd.size <= pos) return Request.Malformed
        val blockCount = cmd[pos++].u8()
        if (blockCount !in 1..MAX_BLOCKS) {
            return Request.Rejected(FLAG1_GENERAL, FLAG2_ILLEGAL_BLOCK_COUNT)
        }

        val numbers = ArrayList<Int>(blockCount)
        var rejected: Request.Rejected? = null
        for (element in 1..blockCount) {
            if (pos >= cmd.size) return Request.Malformed
            val d0 = cmd[pos].u8()
            val width = if (d0 and 0x80 != 0) 2 else 3
            if (pos + width > cmd.size) return Request.Malformed
            val number = if (width == 2) {
                cmd[pos + 1].u8()
            } else {
                cmd[pos + 1].u8() or (cmd[pos + 2].u8() shl 8)
            }
            pos += width
            // Keep parsing: a frame with trailing garbage is malformed even
            // when one of its elements is also out of range.
            if (rejected == null && (d0 and 0x0F) >= serviceCount) {
                // Status Flag1 names the offending element by its 1-based position.
                rejected = Request.Rejected(element, FLAG2_ILLEGAL_SERVICE_ORDER)
            }
            numbers += number
        }
        if (pos != cmd.size) return Request.Malformed
        return rejected ?: Request.Blocks(numbers)
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
}
