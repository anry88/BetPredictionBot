import dto.Match

object NeutralVenuePolicy {
    private const val WORLD_CUP_DESCRIPTION = "World World Cup"

    fun isNeutralVenue(match: Match): Boolean {
        val matchType = "${match.league.country} ${match.league.name}"
        return isNeutralVenue(matchType, match.league.round)
    }

    fun isNeutralVenue(matchType: String, round: String?): Boolean {
        if (matchType.equals(WORLD_CUP_DESCRIPTION, ignoreCase = true)) {
            return true
        }

        return isCupFinalRound(round)
    }

    private fun isCupFinalRound(round: String?): Boolean {
        val normalized = round?.trim()?.lowercase() ?: return false
        if (normalized.contains("semi") || normalized.contains("quarter")) {
            return false
        }

        return normalized == "final" ||
                normalized == "finals" ||
                normalized.startsWith("final -") ||
                normalized.endsWith(" - final")
    }
}
