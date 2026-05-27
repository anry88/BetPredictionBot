import kotlin.test.Test
import kotlin.test.assertEquals

class TagNormalizerTest {
    @Test
    fun convertsDiacriticsToAsciiLetters() {
        assertEquals("#AguilasDoradas", TagNormalizer.toTag("Águilas Doradas"))
        assertEquals("#CotedIvoireU20", TagNormalizer.toTag("Côte d'Ivoire U20"))
        assertEquals("#WidzewLodz", TagNormalizer.toTag("Widzew Łódź"))
        assertEquals("#GenclerbirligiSK", TagNormalizer.toTag("Gençlerbirliği S.K."))
    }

    @Test
    fun keepsExistingAsciiTagShape() {
        assertEquals("#BosniaHerzegovinaU17", TagNormalizer.toTag("Bosnia-Herzegovina U17"))
        assertEquals("#1FCHeidenheim", TagNormalizer.toTag("1. FC Heidenheim"))
    }
}
