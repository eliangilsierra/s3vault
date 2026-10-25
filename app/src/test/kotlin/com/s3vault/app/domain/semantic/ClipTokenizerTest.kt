package com.s3vault.app.domain.semantic

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checked against token ids produced by the original tokenizer (Hugging Face `tokenizers` on the same vocab.json and merges.txt):
 * plain phrases, mixed case, tabs and newlines, accents, contractions, an empty query and a query that must be cut.
 * Robolectric only provides org.json here; nothing Android-specific is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipTokenizerTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("clip/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val tokenizer by lazy { ClipTokenizer.parse(resource("vocab.json"), resource("merges.txt")) }

    @Test fun `every reference query produces the ids of the original tokenizer`() {
        val reference = JSONObject(resource("reference_tokens.json"))
        val keys = reference.keys().asSequence().toList()
        assertTrue("the reference holds the interesting cases", keys.size >= 19)
        for (q in keys) {
            val arr = reference.getJSONArray(q)
            val expected = LongArray(arr.length()) { arr.getLong(it) }
            assertArrayEquals("tokens of ${q.take(40).replace("\n", "\\n")}", expected, tokenizer.encode(q))
        }
    }

    @Test fun `the context is always seventy seven ids that start and end the way the model expects`() {
        val ids = tokenizer.encode("Beach sunset")
        assertEquals(77, ids.size)
        assertEquals(49406L, ids.first())
        assertEquals(listOf(49406L, 2117L, 3424L, 49407L), ids.take(4))
        assertTrue("padding repeats the end token", ids.drop(4).all { it == 49407L })
        val long = tokenizer.encode("word ".repeat(300))
        assertEquals(77, long.size)
        assertEquals("a cut query still ends with the end token", 49407L, long.last())
    }

    @Test fun `case, spacing and unicode form do not change the meaning of a query`() {
        val a = tokenizer.encode("My Dog")
        assertArrayEquals(a, tokenizer.encode("  my   dog "))
        assertArrayEquals(a, tokenizer.encode("MY\tDOG"))
        // "é" composed or as "e" + combining accent is the same text after NFC.
        assertArrayEquals(tokenizer.encode("café"), tokenizer.encode("café"))
    }

    @Test fun `an empty query is just start and end`() {
        assertEquals(listOf(49406L, 49407L), tokenizer.encode("").take(2))
    }
}
