package service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpAPIFootballServiceTest {
    @Test
    fun `only postponed and cancelled fixtures are unavailable`() {
        assertTrue(isUnavailableFixtureStatus("PST"))
        assertTrue(isUnavailableFixtureStatus("CANC"))

        listOf(null, "NS", "1H", "HT", "2H", "FT", "AET", "PEN").forEach { status ->
            assertFalse(
                isUnavailableFixtureStatus(status),
                "Fixture with status $status must remain stored"
            )
        }
    }
}
