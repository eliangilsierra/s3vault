package com.s3vault.app.domain.semantic

import java.text.Normalizer

/**
 * The byte-pair tokenizer CLIP's text encoder was trained with (the same algorithm as OpenAI's `SimpleTokenizer` and the Hugging
 * Face fast tokenizer): Unicode NFC, whitespace collapsed, lower case, a regular expression that keeps words, single digits and
 * runs of punctuation, bytes mapped to printable characters, merges applied by rank, `</w>` closing every word.
 *
 * It is pure Kotlin so it can be tested against reference token ids produced by the original tokenizer (see ClipTokenizerTest);
 * a tokenizer that is slightly wrong would not crash, it would silently make every search a little worse.
 */
class ClipTokenizer(private val vocab: Map<String, Int>, merges: List<Pair<String, String>>) {
    private val ranks: Map<Pair<String, String>, Int> = HashMap<Pair<String, String>, Int>(merges.size * 2).also { m -> merges.forEachIndexed { i, p -> m[p] = i } }
    private val cache = HashMap<String, List<String>>()
    private val startId = vocab.getValue(START)
    private val endId = vocab.getValue(END)

    /** Exactly [length] ids: start, the tokens (cut if too long), end, then end again as padding (what the model expects). */
    fun encode(text: String, length: Int = CONTEXT_LENGTH): LongArray {
        val ids = ArrayList<Int>()
        ids += startId
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC).replace(WHITESPACE, " ").lowercase()
        for (m in PATTERN.findAll(clean)) {
            val word = m.value.toByteArray(Charsets.UTF_8).joinToString("") { BYTE_TO_CHAR[it.toInt() and 0xFF].toString() }
            for (piece in bpe(word)) ids += vocab[piece] ?: endId // an unknown piece is the "unknown" token, which is the end token in CLIP
        }
        val body = if (ids.size > length - 1) ids.subList(0, length - 1) else ids
        val out = LongArray(length) { endId.toLong() }
        body.forEachIndexed { i, id -> out[i] = id.toLong() }
        out[body.size] = endId.toLong()
        return out
    }

    private fun bpe(word: String): List<String> = cache.getOrPut(word) {
        val symbols = ArrayList<String>(word.length)
        for (i in word.indices) symbols += if (i == word.lastIndex) word[i] + "</w>" else word[i].toString()
        while (symbols.size > 1) {
            var best = Int.MAX_VALUE
            var at = -1
            for (i in 0 until symbols.size - 1) {
                val r = ranks[symbols[i] to symbols[i + 1]] ?: continue
                if (r < best) { best = r; at = i }
            }
            if (at < 0) break
            val first = symbols[at]; val second = symbols[at + 1]
            var i = 0
            val merged = ArrayList<String>(symbols.size)
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == first && symbols[i + 1] == second) { merged += first + second; i += 2 } else { merged += symbols[i]; i++ }
            }
            symbols.clear(); symbols.addAll(merged)
        }
        symbols
    }

    companion object {
        const val CONTEXT_LENGTH = 77
        private const val START = "<|startoftext|>"
        private const val END = "<|endoftext|>"
        private val WHITESPACE = Regex("\\s+")
        private val PATTERN = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")

        /** GPT-2's reversible byte to printable-character table. */
        private val BYTE_TO_CHAR: CharArray = run {
            val printable = (33..126) + (161..172) + (174..255)
            val out = CharArray(256)
            var extra = 0
            for (b in 0..255) out[b] = if (b in printable) b.toChar() else (256 + extra++).toChar()
            out
        }

        /** [vocabJson] is `vocab.json` ({"token": id}), [mergesTxt] is `merges.txt` (a header line, then "a b" per line). */
        fun parse(vocabJson: String, mergesTxt: String): ClipTokenizer {
            val o = org.json.JSONObject(vocabJson)
            val vocab = HashMap<String, Int>(o.length() * 2)
            val keys = o.keys()
            while (keys.hasNext()) { val k = keys.next(); vocab[k] = o.getInt(k) }
            val merges = mergesTxt.lineSequence().drop(1).filter { it.isNotBlank() }.map { l -> l.substringBefore(' ') to l.substringAfter(' ') }.toList()
            return ClipTokenizer(vocab, merges)
        }
    }
}
