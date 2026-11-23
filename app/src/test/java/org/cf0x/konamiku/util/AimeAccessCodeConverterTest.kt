package org.cf0x.konamiku.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AimeAccessCodeConverterTest {
    private companion object {
        const val IDM = "012E000000114514"
        const val IDM_COMPLEMENT = "FED1FFFFFFEEBAEC"
        const val CODE = "00085005442967749908"
    }

    // ── idmToAccessCode ──

    @Test
    fun `idmToAccessCode converts standard IDm`() {
        // 0x012E000000114514 == 85005442967749908
        assertEquals(
            AimeAccessCodeConverter.Result.Single(CODE),
            AimeAccessCodeConverter.idmToAccessCode(IDM)
        )
    }

    @Test
    fun `idmToAccessCode pads short IDm`() {
        val result = AimeAccessCodeConverter.idmToAccessCode("E004123456789ABC")
        assertTrue(result is AimeAccessCodeConverter.Result.Single)
        val v = (result as AimeAccessCodeConverter.Result.Single).value
        assertEquals(20, v.length)
        assertTrue(v.all { it.isDigit() })
    }

    @Test
    fun `idmToAccessCode rejects empty IDm`() {
        val result = AimeAccessCodeConverter.idmToAccessCode("")
        assertTrue(result is AimeAccessCodeConverter.Result.Failure)
    }

    @Test
    fun `idmToAccessCode rejects overlong IDm`() {
        val result = AimeAccessCodeConverter.idmToAccessCode("0123456789ABCDEF0")
        assertTrue(result is AimeAccessCodeConverter.Result.Failure)
    }

    @Test
    fun `idmToAccessCode rejects non-hex IDm`() {
        val result = AimeAccessCodeConverter.idmToAccessCode("012E0000ZZZZZZZZ")
        assertTrue(result is AimeAccessCodeConverter.Result.Failure)
    }

    // ── accessCodeToIdm ──

    @Test
    fun `accessCodeToIdm returns both candidates of the signed value`() {
        // The forward conversion takes |signed 64-bit|, so IDM and its two's
        // complement map to the same code; the reverse must offer both.
        assertEquals(
            AimeAccessCodeConverter.Result.Ambiguous(IDM, IDM_COMPLEMENT),
            AimeAccessCodeConverter.accessCodeToIdm(CODE)
        )
    }

    @Test
    fun `accessCodeToIdm candidates convert back to the same code`() {
        for (idm in listOf(IDM, IDM_COMPLEMENT)) {
            assertEquals(
                AimeAccessCodeConverter.Result.Single(CODE),
                AimeAccessCodeConverter.idmToAccessCode(idm)
            )
        }
    }

    @Test
    fun `accessCodeToIdm handles formatted input with dashes`() {
        assertEquals(
            AimeAccessCodeConverter.Result.Ambiguous(IDM, IDM_COMPLEMENT),
            AimeAccessCodeConverter.accessCodeToIdm("0008-5005-4429-6774-9908")
        )
    }

    @Test
    fun `accessCodeToIdm returns ambiguous for positive value`() {
        // A small value like "1" can be interpreted as both positive and complement
        val result = AimeAccessCodeConverter.accessCodeToIdm("1")
        assertTrue(result is AimeAccessCodeConverter.Result.Ambiguous)
    }

    @Test
    fun `accessCodeToIdm rejects empty input`() {
        val result = AimeAccessCodeConverter.accessCodeToIdm("")
        assertTrue(result is AimeAccessCodeConverter.Result.Failure)
    }

    @Test
    fun `accessCodeToIdm rejects overlong input`() {
        val result = AimeAccessCodeConverter.accessCodeToIdm("1".repeat(21))
        assertTrue(result is AimeAccessCodeConverter.Result.Failure)
    }

    // ── formatAccessCode ──

    @Test
    fun `formatAccessCode groups digits`() {
        assertEquals("0008-1234-1234-1234-1234", AimeAccessCodeConverter.formatAccessCode("00081234123412341234"))
    }

    @Test
    fun `formatAccessCode strips non-digits`() {
        assertEquals("0008-1234-1234-1234-1234", AimeAccessCodeConverter.formatAccessCode("0008-1234-1234-1234-1234"))
    }

    @Test
    fun `formatAccessCode pads short input`() {
        assertEquals("0000-0000-0000-0000-0123", AimeAccessCodeConverter.formatAccessCode("123"))
    }
}
