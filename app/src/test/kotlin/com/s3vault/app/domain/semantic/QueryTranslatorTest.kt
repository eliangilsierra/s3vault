package com.s3vault.app.domain.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryTranslatorTest {
    @Test fun `common Spanish photo words become English and the screen can show what was run`() {
        val r = QueryTranslator.translate("Playa al atardecer")
        assertEquals("beach at sunset", r.effective)
        assertTrue(r.changed)
        assertEquals("my dog", QueryTranslator.translate("mi perro").effective)
        assertEquals("cars", QueryTranslator.translate("coches").effective)
        assertEquals("receipts", QueryTranslator.translate("Recibos").effective)
        assertEquals("mountain", QueryTranslator.translate("montaña").effective)
    }

    @Test fun `accents and punctuation do not stop a word from matching`() {
        assertEquals("birthday cake", QueryTranslator.translate("cumpleaños, tarta!").effective.replace(",", ""))
        assertEquals("sunset", QueryTranslator.translate("ATARDECER").effective)
    }

    @Test fun `unknown words are kept as they are and an English query is left alone`() {
        val a = QueryTranslator.translate("Machu Picchu")
        assertEquals("Machu Picchu", a.effective)
        assertFalse(a.changed)
        val b = QueryTranslator.translate("beach sunset")
        assertEquals("beach sunset", b.effective)
        assertFalse(b.changed)
    }

    @Test fun `an empty query stays empty`() {
        assertEquals("", QueryTranslator.translate("   ").effective)
        assertFalse(QueryTranslator.translate("").changed)
    }

    @Test fun `mixed queries keep the words they do not know`() {
        assertEquals("dog on Tenerife", QueryTranslator.translate("perro on Tenerife").effective)
    }
}
