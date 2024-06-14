package dto

import com.google.gson.annotations.SerializedName

data class MatchResponse(
    @SerializedName("matches") val matches: List<Match>
)

data class Match(
    @SerializedName("id") val id: Int,
    @SerializedName("homeTeam") val homeTeam: Team,
    @SerializedName("awayTeam") val awayTeam: Team,
    @SerializedName("score") val score: Score
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
