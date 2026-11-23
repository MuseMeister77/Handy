package com.offlinevoice.keyboard

/** Outcome of one dictation, shared by the keyboard and the floating mic. */
sealed class DictationResult {
    /**
     * [aiProblem] is set when AI polish was on but couldn't be used (e.g. "no
     * connection"); [text] is then Spokify's own clean-up.
     */
    data class Text(val text: String, val aiProblem: String? = null) : DictationResult()
    object NoSpeech : DictationResult()
    /** Android gave the app silence instead of audio (mic blocked in the background). */
    object MicBlocked : DictationResult()
    object Failed : DictationResult()
}

object Dictation {
    /**
     * Quick checks before the slow part. Returns a final result if the clip
     * should not be transcribed, or null to go ahead.
     */
    fun precheck(samples: FloatArray, silencedByAndroid: Boolean): DictationResult? = when {
        silencedByAndroid -> DictationResult.MicBlocked
        samples.size >= AudioRecorder.SAMPLE_RATE / 2 && samples.all { it == 0f } ->
            DictationResult.MicBlocked
        isSilent(samples) -> DictationResult.NoSpeech
        else -> null
    }
}
