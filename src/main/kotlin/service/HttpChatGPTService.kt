package service

import `interface`.ChatGPTApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object HttpChatGPTService {
    private val apiKey: String = Config.getProperty("chatgpt.api.key") ?: throw IllegalStateException("API Key not found")

    // Создание экземпляра Retrofit с настройкой клиента
    private val httpClient = createHttpClient(apiKey)
    private val retrofit: Retrofit = Retrofit.Builder()
        .client(httpClient)
        .baseUrl("https://api.openai.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: ChatGPTApi = retrofit.create(ChatGPTApi::class.java)

    // Обертка для выполнения запроса с заголовками
    private fun createHttpClient(apiKey: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .header("Authorization", "Bearer $apiKey")
                    .method(original.method(), original.body())
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .connectTimeout(3, TimeUnit.MINUTES) // Установка таймаута подключения
            .readTimeout(3, TimeUnit.MINUTES) // Установка таймаута чтения
            .writeTimeout(3, TimeUnit.MINUTES) // Установка таймаута записи
            .build()
    }
}
