package dto

import kotlinx.serialization.Serializable

@Serializable
data class OddsResponse(val response: List<OddsData>)

@Serializable
data class OddsData(
    val league: League,
    val fixture: Fixture,
    val update: String,
    val bookmakers: List<Bookmaker>,
)

@Serializable
data class Bookmaker(
    val id: Int,
    val name: String,
    val bets: List<Bet>
)

@Serializable
data class Bet(
    val id: Int,
    val name: String,
    val values: List<BetValue>
)

@Serializable
data class BetValue(
    val value: String?,
    val odd: String
)

@Serializable
data class ApiFootballResponse(val response: List<Match>)

@Serializable
data class Match(
    val fixture: Fixture,
    val league: League,
    val teams: Teams,
    val goals: Goals?,
    val score: Score?,
    val odds: Odds? = null,
    val remainingRequests: String? = null
)

@Serializable
data class Fixture(
    val id: Int,
    val referee: String? = null,
    val timezone: String,
    val date: String,
    val timestamp: Long,
    val venue: Venue? = null,
    val status: Status? = null
)

@Serializable
data class Venue(
    val id: Int?,
    val name: String?,
    val city: String?,
)

@Serializable
data class Status(
    val long: String,
    val short: String,
    val elapsed: Int?,
)

@Serializable
data class League(
    val id: Int,
    val name: String,
    val country: String,
    val logo: String?,
    val flag: String?,
    val season: Int,
    val round: String? = null,
)

@Serializable
data class Teams(
    val home: Team,
    val away: Team,
)

@Serializable
data class Team(
    val id: Int,
    val name: String,
    val logo: String?,
    val winner: Boolean?,
)

@Serializable
data class Goals(
    val home: Int?,
    val away: Int?,
)

@Serializable
data class Odds(
    val homeWin: Double?,
    val draw: Double?,
    val awayWin: Double?,
)

@Serializable
data class Score(
    val halftime: TimeScore? = null,
    val fulltime: TimeScore? = null,
    val extratime: TimeScore? = null,
    val penalty: TimeScore? = null,
)

@Serializable
data class TimeScore(
    val home: Int? = null,
    val away: Int? = null,
)

