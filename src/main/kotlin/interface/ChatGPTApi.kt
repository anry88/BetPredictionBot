import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.util.PrimitiveIterator

interface ChatGPTApi {
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun getMatchPredictions(@Body request: ChatGPTRequest): ChatGPTResponse
}

data class ChatGPTRequest(
    val model: String,
    val messages: List<Message>,
    val max_tokens: Int,
    val temperature: Double
)

data class Message(
    val role: String,
    val content: String
)

data class ChatGPTResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: Message
)
