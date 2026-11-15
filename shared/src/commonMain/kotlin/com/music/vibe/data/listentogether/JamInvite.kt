package com.music.vibe.data.listentogether

/**
 * The public invite URL for a party, and the only shape that is accepted back.
 *
 * Shared because both applications have to agree on it exactly: a link built on one and opened on
 * the other must resolve to the same code, and a link that is not this shape must be refused on
 * both rather than half-parsed into a join attempt.
 */
object JamInvite {

    const val ORIGIN = "https://iam4tharv.cyou"
    private const val LEGACY_ORIGIN = "https://bitchord.kushagrasingh.in"

    /** How many characters a party code carries. */
    const val CODE_LENGTH = 6

    /** The normalized party code, for the public invite URL shape only. */
    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val prefix = "$ORIGIN/invite/"
        val legacyPrefix = "$LEGACY_ORIGIN/invite/"
        val matchedPrefix = when {
            text.startsWith(prefix, ignoreCase = true) -> prefix
            text.startsWith(legacyPrefix, ignoreCase = true) -> legacyPrefix
            else -> return null
        }
        val code = text.substring(matchedPrefix.length).substringBefore('?').substringBefore('#')
        if (code.length != CODE_LENGTH || !code.all(Char::isLetterOrDigit)) return null
        return code.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/invite/${code.uppercase()}"
}
