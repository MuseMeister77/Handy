package com.offlinevoice.keyboard

/**
 * Turns raw Whisper output into text ready to type. Pure Kotlin, no Android
 * dependencies, so it is covered by plain JVM unit tests.
 */
object TextCleanup {

    data class Options(
        val removeFillers: Boolean = true,
        val spokenPunctuation: Boolean = true,
    )

    // Hesitation sounds. "er" and "ah" are only removed as whole words.
    // Groups: 1 = comma before, 2 = separator before, 3 = punctuation after.
    private val FILLER = Regex(
        """(?i)(,?)(^|[\s,.;:!?])(?:u+m+|u+h+m*|e+r+m+|e+r+|a+h+|h+m+|m+h*m+)(?=$|[\s,.;:!?])([,.]?)""",
    )

    // Non-speech annotations Whisper sometimes adds, e.g. "[BLANK_AUDIO]",
    // "(upbeat music)", "*laughs*".
    private val ANNOTATION = Regex("""\[[^\]]*]|\((?:[a-z]+ ?){1,3}\)|\*[a-z ]{1,30}\*""")

    private data class Command(val pattern: Regex, val replacement: String)

    // Spoken punctuation. Deliberately excludes words that are common in
    // normal speech: "period" ("the reporting period"), "colon", "dash".
    private val COMMANDS: List<Command> = listOf(
        "new paragraph" to "\n\n",
        "new line" to "\n",
        "newline" to "\n",
        "next line" to "\n",
        "full stop" to ".",
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "semicolon" to ";",
        "comma" to ",",
    ).map { (words, out) ->
        val body = words.split(" ").joinToString("""\s+""")
        // Swallow the punctuation Whisper puts around the spoken command, so
        // "Hello, comma, world." becomes "Hello, world." A line break keeps
        // the sentence end before it: "Done. New line." stays "Done.\n".
        val before = if (out.startsWith("\n")) """[ \t,;:]*""" else """[\s,.;:!?]*"""
        Command(Regex("""(?i)$before\b$body\b[,.;:!?]*"""), out)
    }

    fun clean(raw: String, options: Options = Options()): String {
        var t = removeRepeatedSentences(raw.replace(ANNOTATION, " "))
        if (options.removeFillers) {
            t = FILLER.replace(t) { m ->
                val (leadComma, sep, trail) = m.destructured
                // "think, uh, we" -> "think we"; "left um. Then" -> "left. Then"
                val keep = when {
                    trail == "." -> "."
                    leadComma.isNotEmpty() && trail.isEmpty() -> ","
                    else -> ""
                }
                keep + sep
            }
        }
        if (options.spokenPunctuation) {
            for (c in COMMANDS) {
                t = c.pattern.replace(t) { MARK_START + c.replacement + MARK_END }
            }
        }
        return normalise(t)
    }

    private val SENTENCE = Regex("""[^.!?]+[.!?]*""")

    /**
     * Whisper occasionally outputs the same sentence twice in a row (a known
     * glitch). People almost never repeat a whole sentence word for word
     * straight away, so a back-to-back duplicate of a sentence of 3+ words
     * is dropped.
     */
    internal fun removeRepeatedSentences(t: String): String {
        val out = StringBuilder()
        var previousKey = ""
        for (m in SENTENCE.findAll(t)) {
            val sentence = m.value
            val key = sentence.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex(" +"), " ")
            if (key.isNotEmpty() && key == previousKey && key.count { it == ' ' } >= 2) continue
            out.append(sentence)
            if (key.isNotEmpty()) previousKey = key
        }
        return out.toString()
    }

    private const val MARK_START = '\u0001'
    private const val MARK_END = '\u0002'

    private fun normalise(input: String): String {
        var t = input
        // Punctuation from spoken commands: glue to the previous word and
        // make sure a space follows (except for line breaks).
        t = t.replace(Regex("""\s*$MARK_START([^$MARK_END]*)$MARK_END\s*""")) { m ->
            val p = m.groupValues[1]
            if (p.startsWith("\n")) p else "$p "
        }
        t = t.replace(Regex("[ \t]+"), " ")
        t = t.replace(Regex(" *\n *"), "\n")
        t = t.replace(Regex(""" +([,.;:!?])"""), "$1")
        // Collapse runs like ",." or ", ," left behind by removed words.
        t = t.replace(Regex("""([,;:])(?:\s*[,;:])+"""), "$1")
        t = t.replace(Regex(""",\s*([.!?])"""), "$1")
        t = t.replace(Regex("""([.!?])\s*[,;]"""), "$1")
        // Drop punctuation left dangling at the very start or after a line break.
        t = t.replace(Regex("""(^|\n)[ \t,;:.]+"""), "$1")
        t = t.trim(' ', '\t')
        t = t.trimStart('\n').trimEnd(' ')
        return capitaliseSentences(t)
    }

    /** Capitalises the first letter of the text and of each new sentence/line. */
    internal fun capitaliseSentences(t: String): String {
        val sb = StringBuilder(t.length)
        var capNext = true
        for (ch in t) {
            if (capNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capNext = false
            } else {
                sb.append(ch)
                if (ch.isLetterOrDigit()) capNext = false
            }
            if (ch == '.' || ch == '?' || ch == '!' || ch == '\n') capNext = true
        }
        return sb.toString()
    }

    // Words that are capitalised only because they start a sentence. If the
    // new dictation continues an unfinished sentence, these go lower case.
    // Names and terms such as "Oracle" are left alone.
    private val COMMON_STARTERS = setOf(
        "a", "an", "the", "and", "but", "or", "so", "then", "also", "because",
        "to", "for", "in", "on", "of", "at", "with", "from", "by", "as", "if",
        "that", "this", "these", "those", "which", "who", "when", "where",
        "it", "its", "we", "you", "they", "he", "she", "my", "our", "your",
        "their", "his", "her", "is", "are", "was", "were", "will", "can",
        "could", "would", "should", "please", "not", "just", "all", "any",
        "some", "there", "here", "what", "how", "why", "after", "before",
        "until", "while", "maybe", "still", "now", "only", "even", "about",
    )

    /**
     * Fits [text] to what is already in the field before the cursor:
     * adds a leading space when needed, and keeps or drops the leading
     * capital depending on whether a new sentence starts here.
     */
    fun fitToContext(text: String, before: CharSequence?): String {
        if (text.isEmpty()) return text
        val prev = before?.toString().orEmpty()
        val trimmedPrev = prev.trimEnd(' ', '\t')
        val startsSentence = trimmedPrev.isEmpty() ||
            trimmedPrev.last().let { it == '.' || it == '?' || it == '!' || it == '\n' }

        val out = if (startsSentence) text else lowercaseCommonFirstWord(text)

        val needsSpace = prev.isNotEmpty() &&
            !prev.last().isWhitespace() &&
            !out.first().isWhitespace() &&
            out.first() !in ",.;:!?)"
        return if (needsSpace) " $out" else out
    }

    private fun lowercaseCommonFirstWord(text: String): String {
        val end = text.indexOfFirst { !it.isLetter() && it != '\'' }.let { if (it < 0) text.length else it }
        if (end == 0) return text
        val word = text.substring(0, end)
        // Leave "I", "I'm", acronyms and anything with inner capitals alone.
        if (word.drop(1).any { it.isUpperCase() }) return text
        if (word.lowercase() !in COMMON_STARTERS) return text
        return word.lowercase() + text.substring(end)
    }

    /** Result of putting dictated text into existing text at the cursor. */
    data class Splice(val text: String, val cursor: Int, val inserted: String)

    /**
     * Inserts [dictated] into [current] at the selection (replacing any
     * selected text). Bad or missing selection values mean "at the end".
     */
    fun spliceAtCursor(current: String, selStart: Int, selEnd: Int, dictated: String): Splice {
        var start = selStart
        var end = selEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length
            end = current.length
        }
        if (start > end) start = end.also { end = start }
        val before = current.substring(0, start)
        val after = current.substring(end)
        var piece = fitToContext(dictated, before)
        // Keep a space between the dictation and a word right after the cursor.
        if (piece.isNotEmpty() && after.isNotEmpty() && !piece.last().isWhitespace() &&
            after.first().isLetterOrDigit()
        ) {
            piece += " "
        }
        return Splice(before + piece + after, before.length + piece.length, piece)
    }
}
