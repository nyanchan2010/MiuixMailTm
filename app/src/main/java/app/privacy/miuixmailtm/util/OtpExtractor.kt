package app.privacy.miuixmailtm.util

object OtpExtractor {
    private val KEYWORD_OTP_REGEX = Regex(
        pattern = "(?:code|otp|pin|passcode|verification|verify|token|コード|验证码|인증번호)[^A-Za-z0-9]{0,20}([A-Z0-9]{4,8})\\b",
        option = RegexOption.IGNORE_CASE
    )

    private val STANDALONE_NUMERIC_OTP_REGEX = Regex(
        pattern = "\\b(\\d{4,8})\\b"
    )

    private val HTML_TAG_REGEX = Regex("<[^>]*>")

    fun stripHtml(input: String): String {
        return input
            .replace("<br>", "\n", ignoreCase = true)
            .replace("<br/>", "\n", ignoreCase = true)
            .replace("</p>", "\n\n", ignoreCase = true)
            .replace(HTML_TAG_REGEX, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    fun extractOtp(subject: String, bodyText: String): String? {
        val combined = "$subject\n$bodyText"

        KEYWORD_OTP_REGEX.findAll(combined).forEach { match ->
            val candidate = match.groupValues[1]
            if (candidate.any { it.isDigit() } && !isLikelyYear(candidate)) {
                return candidate
            }
        }

        STANDALONE_NUMERIC_OTP_REGEX.findAll(combined).forEach { match ->
            val candidate = match.groupValues[1]
            if (!isLikelyYear(candidate)) {
                return candidate
            }
        }

        return null
    }

    private fun isLikelyYear(code: String): Boolean {
        if (code.length != 4) return false
        val num = code.toIntOrNull() ?: return false
        return num in 1990..2035
    }
}
