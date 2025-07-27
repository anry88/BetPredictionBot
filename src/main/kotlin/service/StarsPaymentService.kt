package service

import Config
import FootballBot
import org.telegram.telegrambots.meta.api.methods.payments.SendInvoice
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import repository.SubscriptionType

class StarsPaymentService(private val bot: FootballBot) {
    private val providerToken: String =
        Config.getProperty("provider.token") ?: ""

    private val botPrices = mapOf(1 to 500, 3 to 1200)
    private val channelPrices = mapOf(1 to 700, 3 to 1500)

    fun sendPremiumInvoice(chatId: String, type: SubscriptionType, months: Int) {
        val title = when (type) {
            SubscriptionType.BOT -> if (months == 1) "Bot Premium 1M" else "Bot Premium 3M"
            SubscriptionType.CHANNEL -> if (months == 1) "Channel Access 1M" else "Channel Access 3M"
        }
        val payload = "${type.name.lowercase()}_${if (months == 3) "3m" else "1m"}"
        val typeText = type.name.lowercase().replaceFirstChar { it.uppercase() }
        val description = "$typeText subscription for $months month(s)"
        val price = when (type) {
            SubscriptionType.BOT -> botPrices.getValue(months)
            SubscriptionType.CHANNEL -> channelPrices.getValue(months)
        }
        val invoice = SendInvoice()
        invoice.chatId = chatId
        invoice.title = title
        invoice.description = description
        invoice.payload = payload
        invoice.providerToken = providerToken
        invoice.currency = "USD"
        invoice.prices = listOf(LabeledPrice(title, price))
        bot.execute(invoice)
    }
}
