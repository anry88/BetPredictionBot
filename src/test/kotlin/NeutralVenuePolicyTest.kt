import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeutralVenuePolicyTest {
    @Test
    fun treatsWorldCupMatchesAsNeutral() {
        assertTrue(NeutralVenuePolicy.isNeutralVenue("World World Cup", "Group Stage - 1"))
    }

    @Test
    fun treatsCupFinalRoundsAsNeutral() {
        assertTrue(NeutralVenuePolicy.isNeutralVenue("World UEFA Champions League", "Final"))
        assertTrue(NeutralVenuePolicy.isNeutralVenue("World UEFA Europa League", "Final - 1"))
    }

    @Test
    fun doesNotTreatEarlierFinalStagesAsNeutral() {
        assertFalse(NeutralVenuePolicy.isNeutralVenue("World UEFA Champions League", "Semi-finals"))
        assertFalse(NeutralVenuePolicy.isNeutralVenue("World UEFA Champions League", "Quarter-finals"))
        assertFalse(NeutralVenuePolicy.isNeutralVenue("England Premier League", "Regular Season - 1"))
    }
}
