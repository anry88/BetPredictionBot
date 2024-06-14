package dto

import com.google.gson.annotations.SerializedName

data class MatchResponse(
    @SerializedName("matches") val matches: List<Match>
)

data class Match(
    @SerializedName("id") val id: Int,
    @SerializedName("utcDate") val utcDate: String,
    @SerializedName("competition") val competition: Competition,
    @SerializedName("homeTeam") val homeTeam: Team,
    @SerializedName("awayTeam") val awayTeam: Team,
    @SerializedName("score") val score: Score
)

data class Competition(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

data class Team(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

data class Score(
    @SerializedName("fullTime") val fullTime: FullTime
)

data class FullTime(
    @SerializedName("homeTeam") val homeTeam: Int?,
    @SerializedName("awayTeam") val awayTeam: Int?
)
