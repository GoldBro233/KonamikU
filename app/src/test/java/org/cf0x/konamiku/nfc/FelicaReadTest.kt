package org.cf0x.konamiku.nfc

import kotlin.test.Test
import kotlin.test.assertEquals

class FelicaReadTest {

    private val idm = "0123456789ABCDEF".chunked(2).map { it.toInt(16) }

    /** `len 06 IDm n services… m elements…`, with the length byte filled in. */
    private fun frame(services: List<Int>, elements: List<Int>): ByteArray {
        val body = listOf(0x06) + idm + services.size + services.flatMap { listOf(it and 0xFF, it shr 8) } +
            elements
        return (listOf(body.size + 1) + body).map { it.toByte() }.toByteArray()
    }

    private fun frameWithCount(services: List<Int>, blockCount: Int, elements: List<Int>): ByteArray {
        val body = listOf(0x06) + idm + services.size + services.flatMap { listOf(it and 0xFF, it shr 8) } +
            blockCount + elements
        return (listOf(body.size + 1) + body).map { it.toByte() }.toByteArray()
    }

    private fun blocks(vararg n: Int) = FelicaRead.Request.Blocks(n.toList())

    @Test
    fun `two-byte element takes the block number from the second byte`() {
        assertEquals(blocks(0x82), FelicaRead.parse(frameWithCount(listOf(0x000B), 1, listOf(0x80, 0x82))))
    }

    @Test
    fun `consecutive two-byte elements stay aligned`() {
        assertEquals(
            blocks(0x82, 0x86),
            FelicaRead.parse(frameWithCount(listOf(0x000B), 2, listOf(0x80, 0x82, 0x80, 0x86)))
        )
    }

    @Test
    fun `three-byte element reads a little-endian block number`() {
        assertEquals(blocks(0x100), FelicaRead.parse(frameWithCount(listOf(0x000B), 1, listOf(0x00, 0x00, 0x01))))
    }

    @Test
    fun `mixed element lengths and service orders`() {
        assertEquals(
            blocks(0x01, 0x0234, 0x85),
            FelicaRead.parse(
                frameWithCount(
                    listOf(0x000B, 0x100B),
                    3,
                    listOf(0x80, 0x01, 0x01, 0x34, 0x02, 0x81, 0x85)
                )
            )
        )
    }

    @Test
    fun `truncated element is malformed`() {
        assertEquals(
            FelicaRead.Request.Malformed,
            FelicaRead.parse(frameWithCount(listOf(0x000B), 2, listOf(0x80, 0x82, 0x00, 0x00)))
        )
    }

    @Test
    fun `missing element is malformed`() {
        assertEquals(
            FelicaRead.Request.Malformed,
            FelicaRead.parse(frameWithCount(listOf(0x000B), 2, listOf(0x80, 0x82)))
        )
    }

    @Test
    fun `trailing bytes are malformed`() {
        assertEquals(
            FelicaRead.Request.Malformed,
            FelicaRead.parse(frameWithCount(listOf(0x000B), 1, listOf(0x80, 0x82, 0x00)))
        )
    }

    @Test
    fun `truncated service list is malformed`() {
        val full = frame(listOf(0x000B), emptyList())
        assertEquals(FelicaRead.Request.Malformed, FelicaRead.parse(full.copyOf(full.size - 1)))
    }

    @Test
    fun `service order beyond the service count is rejected at its position`() {
        assertEquals(
            FelicaRead.Request.Rejected(2, FelicaRead.FLAG2_ILLEGAL_SERVICE_ORDER),
            FelicaRead.parse(frameWithCount(listOf(0x000B), 2, listOf(0x80, 0x82, 0x81, 0x83)))
        )
    }

    @Test
    fun `zero services is an illegal service count`() {
        assertEquals(
            FelicaRead.Request.Rejected(FelicaRead.FLAG1_GENERAL, FelicaRead.FLAG2_ILLEGAL_SERVICE_COUNT),
            FelicaRead.parse(frameWithCount(emptyList(), 1, listOf(0x80, 0x82)))
        )
    }

    @Test
    fun `seventeen services is an illegal service count`() {
        assertEquals(
            FelicaRead.Request.Rejected(FelicaRead.FLAG1_GENERAL, FelicaRead.FLAG2_ILLEGAL_SERVICE_COUNT),
            FelicaRead.parse(frameWithCount(List(17) { 0x000B }, 1, listOf(0x80, 0x82)))
        )
    }

    @Test
    fun `more blocks than one response can carry is an illegal block count`() {
        val elements = List(16) { listOf(0x80, it) }.flatten()
        assertEquals(
            FelicaRead.Request.Rejected(FelicaRead.FLAG1_GENERAL, FelicaRead.FLAG2_ILLEGAL_BLOCK_COUNT),
            FelicaRead.parse(frameWithCount(listOf(0x000B), 16, elements))
        )
    }
}
