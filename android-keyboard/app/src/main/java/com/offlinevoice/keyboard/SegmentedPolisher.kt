package com.offlinevoice.keyboard

/**
 * Polishes long dictations in pieces. Small, fast models handle a couple of
 * hundred words well, but given a long explanation (690 words in testing)
 * gemini-3.5-flash-lite returned it as one unpunctuated sentence. So long
 * text is split at sentence ends into pieces of about [maxWords] words,
 * each polished on its own, one after another (so a free API key's
 * per-minute limit isn't hit all at once), then joined back together.
 *
 * If a piece fails, that piece keeps Spokify's own text and the problem is
 * reported; the rest is still polished.
 */
class SegmentedPolisher(
    private val inner: TextPolisher,
    private val style: PolishStyle,
    private val maxWords: Int = 200,
) : TextPolisher {

    override fun polish(text: String): PolishResult {
        val pieces = split(text, maxWords)
        if (pieces.size <= 1) return inner.polish(text)
        val out = ArrayList<String>(pieces.size)
        var problem: String? = null
        var polishedAny = false
        for (piece in pieces) {
            when (val r = inner.polish(piece)) {
                is PolishResult.Ok -> {
                    out += r.text.trim()
                    polishedAny = true
                }
                is PolishResult.Partial -> {
                    out += r.text.trim()
                    polishedAny = true
                    if (problem == null) problem = r.reason
                }
                is PolishResult.Failed -> {
                    out += piece.trim()
                    if (problem == null) problem = r.reason
                }
            }
        }
        if (!polishedAny) return PolishResult.Failed(problem ?: "unexpected reply")
        val joined = out.joinToString(if (style == PolishStyle.POLISHED) "\n\n" else " ")
        // Part of it was polished: type that, but say what went wrong.
        return if (problem == null) PolishResult.Ok(joined) else PolishResult.Partial(joined, "part of it: $problem")
    }

    companion object {
        private val SENTENCE_END = Regex("""(?<=[.!?])\s+|\n+""")
        private val SOFT_BREAK = Regex("""(?<=,)\s+|\s+(?=(?:and|so|but|then|because)\b)""", RegexOption.IGNORE_CASE)

        private fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

        /**
         * Splits [text] into pieces of at most [maxWords] words, at sentence
         * ends where possible, else at commas or words like "and", else at
         * the word limit. Joining the pieces with spaces gives back the
         * original words in order.
         */
        fun split(text: String, maxWords: Int): List<String> {
            if (wordCount(text) <= maxWords) return listOf(text)
            val units = text.split(SENTENCE_END).filter { it.isNotBlank() }.flatMap { sentence ->
                if (wordCount(sentence) <= maxWords) listOf(sentence) else splitLong(sentence, maxWords)
            }
            val pieces = ArrayList<String>()
            val current = StringBuilder()
            for (u in units) {
                if (current.isNotEmpty() && wordCount(current.toString()) + wordCount(u) > maxWords) {
                    pieces += current.toString().trim()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(u.trim())
            }
            if (current.isNotBlank()) pieces += current.toString().trim()
            return pieces
        }

        /** A single sentence longer than [maxWords]: break at commas or "and"/"so"…, then by count. */
        private fun splitLong(sentence: String, maxWords: Int): List<String> {
            val parts = sentence.split(SOFT_BREAK).filter { it.isNotBlank() }
            val out = ArrayList<String>()
            val cur = StringBuilder()
            fun flush() {
                if (cur.isNotBlank()) out += cur.toString().trim()
                cur.setLength(0)
            }
            for (p in parts) {
                val words = p.trim().split(Regex("\\s+"))
                if (words.size > maxWords) {
                    flush()
                    words.chunked(maxWords).forEach { out += it.joinToString(" ") }
                    continue
                }
                if (cur.isNotEmpty() && wordCount(cur.toString()) + words.size > maxWords) flush()
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(p.trim())
            }
            flush()
            return out
        }
    }
}
