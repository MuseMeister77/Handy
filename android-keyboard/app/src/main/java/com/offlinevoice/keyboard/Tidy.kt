package com.offlinevoice.keyboard

/**
 * Rule-based tidying of dictated text: pause dots, self-corrections and
 * small grammar slips. Pure Kotlin, unit tested. Rules are deliberately
 * conservative: when unsure, the text is left as spoken.
 */
object Tidy {

    // ---- Pauses ------------------------------------------------------------

    private val DOTS = Regex("""[ \t]*(?:\.{2,}|…)[ \t]*""")

    /**
     * Whisper writes "..." where you pause ("I think... we should"). A pause
     * is not punctuation: mid-sentence the dots go and the sentence just
     * continues; before a clear new sentence they become a full stop.
     */
    fun removePauseDots(t: String): String {
        val segments = DOTS.split(t)
        var out = segments[0]
        for (seg in segments.drop(1)) {
            val before = out.trimEnd()
            out = when {
                before.isEmpty() -> seg // dots at the very start
                before.last() in ".!?\n" -> "$before $seg" // dots after a sentence end
                seg.isEmpty() -> "$before." // dots at the very end
                seg.startsWith("\n") -> "$before.$seg"
                seg.first() in ",;:.!?" -> before + seg
                startsNewSentence(seg) -> "$before. $seg"
                else -> "$before " + lowercaseCommonFirstWord(seg)
            }
        }
        return out
    }

    /**
     * After "...", a capitalised common word ("I think... We should")
     * usually continues the sentence; another capitalised word ("Thanks",
     * a name) is treated as starting a new sentence.
     */
    private fun startsNewSentence(next: String): Boolean {
        val word = next.takeWhile { it.isLetter() || it == '\'' }
        if (word.isEmpty() || !word.first().isUpperCase() || word == "I") return false
        return word.lowercase() !in TextCleanup.COMMON_STARTERS
    }

    /**
     * Joins the pieces transcribed while you spoke. Each piece ends at a
     * pause, which Whisper often marks with "..."; it also capitalises the
     * start of every piece, even mid-sentence. After trailing dots the next
     * piece continues the sentence, so its first word goes lower case unless
     * it's "I", an acronym, or one of [keepCapitalised] (custom words).
     */
    fun joinPieces(parts: List<String>, keepCapitalised: Set<String> = emptySet()): String {
        val keep = keepCapitalised.map { it.lowercase() }.toSet()
        val sb = StringBuilder()
        for (raw in parts) {
            val p = raw.trim()
            if (p.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(p)
                continue
            }
            val prev = sb.toString().trimEnd()
            val trailedOff = prev.endsWith("..") || prev.endsWith("…")
            // No full stop, question or exclamation mark: the sentence wasn't
            // finished when the pause came.
            val unfinished = prev.last().isLetterOrDigit() || prev.last() in ",;:"
            if (trailedOff || unfinished) {
                sb.setLength(prev.trimEnd('.', '…', ' ').length)
                sb.append(' ').append(lowercaseFirstWord(p, keep))
            } else {
                sb.append(' ').append(p)
            }
        }
        return sb.toString()
    }

    private fun lowercaseFirstWord(p: String, keep: Set<String>): String {
        val word = p.takeWhile { it.isLetter() || it == '\'' }
        if (word.isEmpty() || word == "I" || word.startsWith("I'") || word.drop(1).any { it.isUpperCase() }) return p
        if (word.lowercase() in keep) return p
        return word.lowercase() + p.substring(word.length)
    }

    private fun lowercaseCommonFirstWord(p: String): String {
        val word = p.takeWhile { it.isLetter() || it == '\'' }
        if (word.isEmpty() || word == "I" || word.drop(1).any { it.isUpperCase() }) return p
        return if (word.lowercase() in TextCleanup.COMMON_STARTERS) word.lowercase() + p.substring(word.length) else p
    }

    // ---- Self-corrections ----------------------------------------------------

    /**
     * Phrases people use to correct themselves mid-sentence. Bare "no" and
     * "sorry" are left out on purpose: they are too often meant literally
     * ("I can't make it, sorry, I'm busy").
     */
    private val CORRECTION = Regex(
        """(?i),?\s*\b(?:no,?\s+wait|wait,?\s+no|no,?\s+sorry|sorry,?\s+(?:no,?\s+)?i\s+mean|no,?\s+i\s+mean|i\s+mean,?\s+no|""" +
            """no,?\s+actually|actually,?\s+no|or\s+rather)\b[,:]?\s*""",
    )
    private val SCRATCH = Regex("""(?i)[,.]?\s*\b(?:scratch\s+that|delete\s+that)\b[,.!]?\s*""")

    fun fixSelfCorrections(text: String): String {
        var t = text
        repeat(8) {
            val next = applyScratch(t) ?: applyCorrection(t) ?: return t
            t = next
        }
        return t
    }

    /** "Meet Friday. Scratch that. Meet Monday." -> "Meet Monday." */
    private fun applyScratch(t: String): String? {
        val m = SCRATCH.find(t) ?: return null
        val before = t.substring(0, m.range.first)
        val after = t.substring(m.range.last + 1)
        // Drop the sentence (or clause) just before the command.
        val trimmed = before.trimEnd(' ', '.', ',', '!', '?')
        val cut = trimmed.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        val kept = if (cut < 0) "" else trimmed.substring(0, cut + 1) + " "
        return kept + after
    }

    /** "Meet on Friday, no wait, Monday." -> "Meet on Monday." */
    private fun applyCorrection(t: String): String? {
        for (m in CORRECTION.findAll(t)) {
            val beforeAll = t.substring(0, m.range.first)
            val sentenceStart = beforeAll.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' } + 1
            val before = beforeAll.substring(sentenceStart)
            val beforeWords = before.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (beforeWords.isEmpty()) continue // "No wait, ..." at the start: leave it

            val afterAll = t.substring(m.range.last + 1)
            val clauseEnd = afterAll.indexOfFirst { it in ",.!?;\n" }.let { if (it < 0) afterAll.length else it }
            val correction = afterAll.substring(0, clauseEnd).trim()
            val corrWords = correction.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (corrWords.isEmpty()) continue

            val keep = beforeWords.size - wordsReplaced(beforeWords, corrWords)
            val newBefore = beforeWords.take(keep).joinToString(" ")
            val head = t.substring(0, sentenceStart)
            val sep = if (head.isEmpty() || head.endsWith("\n")) "" else " "
            val body = listOf(newBefore, correction).filter { it.isNotEmpty() }.joinToString(" ")
            return (head.trimEnd(' ') + sep + body + afterAll.substring(clauseEnd)).trimStart()
        }
        return null
    }

    /**
     * How many words before the correction it replaces:
     * - If the correction starts with a word said just before ("at 3, no
     *   wait, at 4"), everything from that word.
     * - If it's a name or capitalised term, the capitalised words before
     *   ("John Smith, no wait, Sarah Lee").
     * - Otherwise the single word before ("Friday, no wait, Monday").
     */
    private fun wordsReplaced(before: List<String>, correction: List<String>): Int {
        val first = correction.first().lowercase().trim(',', '.')
        for (i in before.indices.reversed().take(6)) {
            if (before[i].lowercase().trim(',', '.') == first) return before.size - i
        }
        if (correction.first().first().isUpperCase()) {
            val caps = before.takeLastWhile { it.first().isUpperCase() }.size
            if (caps in 1 until before.size) return caps
        }
        return 1
    }

    // ---- Grammar ---------------------------------------------------------------

    // Words that are fine doubled ("had had", "very very", "no no").
    private val OK_DOUBLED = setOf(
        "had", "that", "very", "really", "so", "no", "yes", "bye", "ha", "haha", "more",
        "far", "well", "now", "go", "again", "knock", "there", "is", "please", "hey",
    )
    private val DOUBLED = Regex("""(?i)\b([a-z']+)(\s+\1)+\b""")
    private val LONE_I = Regex("""\bi(?=\b|'(?:m|ve|ll|d)\b)""")
    private val A_AN = Regex("""\b([Aa]n?)\s+([A-Za-z][\w'-]*)""")

    // Vowel letter but consonant sound, and the reverse.
    private val A_NOT_AN = listOf("uni", "use", "usu", "uti", "euro", "eu", "one", "once", "ubi", "ura")
    private val AN_NOT_A = listOf("hour", "honest", "honor", "honour", "heir")

    fun fixGrammar(text: String): String {
        var t = DOUBLED.replace(text) { m ->
            if (m.groupValues[1].lowercase() in OK_DOUBLED) m.value else m.groupValues[1]
        }
        t = LONE_I.replace(t, "I")
        t = A_AN.replace(t) { m ->
            val article = m.groupValues[1]
            val word = m.groupValues[2]
            // Leave acronyms ("an MBA", "a URL") alone: hard to judge by spelling.
            if (word.length > 1 && word.all { it.isUpperCase() || it.isDigit() }) return@replace m.value
            val lower = word.lowercase()
            val wantsAn = when {
                AN_NOT_A.any { lower.startsWith(it) } -> true
                A_NOT_AN.any { lower.startsWith(it) } -> false
                else -> lower.first() in "aeiou"
            }
            val fixed = if (wantsAn) "an" else "a"
            val cased = if (article.first().isUpperCase()) fixed.replaceFirstChar { it.uppercase() } else fixed
            "$cased $word"
        }
        // Missing space after a comma between words ("Hi,John").
        t = Regex("""([A-Za-z]),([A-Za-z])""").replace(t, "$1, $2")
        return t
    }

    /** Ends the text with a full stop if it stops on a word. */
    fun ensureFinalPunctuation(t: String): String {
        val trimmed = t.trimEnd()
        if (trimmed.isEmpty()) return t
        return if (trimmed.last().isLetterOrDigit()) "$trimmed." else trimmed
    }
}
