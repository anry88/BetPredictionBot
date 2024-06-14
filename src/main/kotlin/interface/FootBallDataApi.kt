import dto.MatchResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface FootBallDataApi {
    @GET("v4/matches")
    fun getMatches(
        @Query("status") status: String,
        @Query("dateFrom") dateFrom: String,
        @Query("dateTo") dateTo: String
    ): Call<MatchResponse>
}

