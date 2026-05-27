import java.text.Normalizer
import kotlin.math.absoluteValue

object TagNormalizer {
    private val specialCharacterReplacements = mapOf(
        'Æ' to "AE",
        'æ' to "ae",
        'Œ' to "OE",
        'œ' to "oe",
        'ẞ' to "SS",
        'ß' to "ss",
        'Þ' to "Th",
        'þ' to "th",
        'Ð' to "D",
        'ð' to "d",
        'Ł' to "L",
        'ł' to "l",
        'Đ' to "D",
        'đ' to "d",
        'Ø' to "O",
        'ø' to "o",
        'Å' to "A",
        'å' to "a",
        'İ' to "I",
        'ı' to "i",
        'Ş' to "S",
        'ş' to "s",
        'Ğ' to "G",
        'ğ' to "g",
        'Ç' to "C",
        'ç' to "c"
    )

    fun toTag(name: String): String {
        val transliterated = name.asSequence()
            .joinToString(separator = "") { specialCharacterReplacements[it] ?: it.toString() }

        val withoutMarks = Normalizer
            .normalize(transliterated, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

        val slug = withoutMarks.replace(Regex("[^A-Za-z0-9]"), "")
        return "#${slug.ifBlank { fallbackSlug(name) }}"
    }

    private fun fallbackSlug(name: String): String {
        return "Team${name.hashCode().toLong().absoluteValue}"
    }
}
