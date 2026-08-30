package service

import dto.League
import dto.LeagueConfig
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `api league identity resolves exact configured competition name`() {
        val configs = listOf(
            LeagueConfig(268, 2026, "Uruguay Primera División - Apertura", premiumSelection = true),
            LeagueConfig(270, 2026, "Uruguay Primera División - Clausura", premiumSelection = true)
        )
        val apiLeague = League(
            id = 270,
            name = "Primera División",
            country = "Uruguay",
            logo = null,
            flag = null,
            season = 2026
        )

        val configuredLeague = configs.single { it.matchesApiIdentity(apiLeague.id, apiLeague.season) }

        assertEquals("Uruguay Primera División - Clausura", configuredLeague.description)
        assertFalse(configuredLeague.matchesApiIdentity(apiLeague.id, 2025))
    }
}
