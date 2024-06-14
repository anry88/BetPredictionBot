package service

import Config
import FootBallDataApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class HttpFootBallDataService : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val requestBuilder = original.newBuilder()
            .header("X-Auth-Token", apiKey)
        val request = requestBuilder.build()
        return chain.proceed(request)
    }
}
private val apiKey: String = Config.getProperty("football-data.api.token") ?: throw IllegalStateException("API Key not found")

private val client = OkHttpClient.Builder()
    .addInterceptor(HttpFootBallDataService())
    .build()

private val retrofit = Retrofit.Builder()
    .baseUrl("https://api.football-data.org/")
    .client(client)
    .addConverterFactory(GsonConverterFactory.create())
    .build()

val footballDataApi: FootBallDataApi = retrofit.create(FootBallDataApi::class.java)
