package service

import Config
import FootBallDataApi
import dto.MatchResponse
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response as RetrofitResponse

class HttpFootBallDataService : Interceptor {
    private val logger = LoggerFactory.getLogger(HttpFootBallDataService::class.java)
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val requestBuilder = original.newBuilder()
            .header("X-Auth-Token", apiKey)
        val request = requestBuilder.build()
        return chain.proceed(request)
    }

    private val apiKey: String = Config.getProperty("football-data.api.token") ?: throw IllegalStateException("API Key not found")

    private val client = OkHttpClient.Builder()
        .addInterceptor(this)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.football-data.org/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val footballDataApi: FootBallDataApi = retrofit.create(FootBallDataApi::class.java)

    fun fetchMatches() {
        val currentDate = LocalDate.now()
        val nextDay = currentDate.plusDays(2)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val formattedCurrentDate = currentDate.format(formatter)
        val formattedNextDay = nextDay.format(formatter)

        val call = footballDataApi.getMatches("SCHEDULED", formattedCurrentDate, formattedNextDay)
        call.enqueue(object : Callback<MatchResponse> {
            override fun onResponse(call: Call<MatchResponse>, response: RetrofitResponse<MatchResponse>) {
                if (response.isSuccessful) {
                    val matches = response.body()?.matches
                    // Process the matches data as needed
                    matches?.forEach { match ->
                        logger.info("Match: ${match.homeTeam.name} vs ${match.awayTeam.name}, Score: ${match.score.fullTime.homeTeam} - ${match.score.fullTime.awayTeam}")
                    }
                } else {
                    logger.error("Request failed with code: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<MatchResponse>, t: Throwable) {
                logger.error("Request failed with error: ${t.message}")
            }
        })
    }
}
