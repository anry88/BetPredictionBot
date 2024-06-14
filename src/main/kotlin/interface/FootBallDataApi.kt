import dto.MatchResponse
import retrofit2.Call
import retrofit2.http.GET

interface FootBallDataApi {
    @GET("v2/matches")
    fun getMatches(): Call<MatchResponse>
}
