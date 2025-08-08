package service

import Config
import FootballBot
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import repository.SubscriptionPlan

class StarsPaymentService(private val bot: FootballBot) {
    private val providerToken: String =
        Config.getProperty("provider.token") ?: "stars"

    private val httpClient = OkHttpClient()

    private val prices = mapOf(
        SubscriptionPlan.BOT_1 to 400,
        SubscriptionPlan.BOT_3 to 1000,
        SubscriptionPlan.CHANNEL_1 to 250,
        SubscriptionPlan.CHANNEL_3 to 650
    )

    fun getPrice(plan: SubscriptionPlan): Int = prices.getValue(plan)

    fun sendPremiumInvoice(chatId: String, plan: SubscriptionPlan) {

        val title = plan.label
        val payload = "${plan.type.name.lowercase()}_${plan.months}"
        val typeText = plan.type.name.lowercase().replaceFirstChar { it.uppercase() }
        val description = "$typeText subscription for ${plan.months} month(s)"
        val price = prices.getValue(plan)
        val invoice = SendInvoice()
        invoice.setChatId(chatId)
        invoice.setTitle(title)
        invoice.setDescription(description)
        invoice.setPayload(payload)
        invoice.setProviderToken(providerToken)
        // Use Telegram Stars for payments
        invoice.setCurrency("XTR")
        invoice.setPrices(listOf(LabeledPrice(title, price)))
        bot.execute(invoice)
    }

    fun refundStars(userId: Long, telegramPaymentChargeId: String) {
        val url = "https://api.telegram.org/bot${bot.getBotToken()}/refundStarPayment"
        val json = "{\"user_id\":$userId,\"telegram_payment_charge_id\":\"$telegramPaymentChargeId\"}"
        val mediaType = MediaType.parse("application/json")
        val body = RequestBody.create(mediaType, json)
        val request = Request.Builder().url(url).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Failed to refund stars: ${response.code()}")
            }
        }
    }
}
