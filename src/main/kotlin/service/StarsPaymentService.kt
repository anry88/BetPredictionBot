package service

import Config
import FootballBot
import org.telegram.telegrambots.meta.api.methods.payments.SendInvoice
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import repository.SubscriptionPlan
import repository.SubscriptionType

class StarsPaymentService(private val bot: FootballBot) {
    private val providerToken: String =
        Config.getProperty("provider.token") ?: ""

    private val prices = mapOf(
        SubscriptionPlan.BOT_1 to 500,
        SubscriptionPlan.BOT_3 to 1200,
        SubscriptionPlan.CHANNEL_1 to 700,
        SubscriptionPlan.CHANNEL_3 to 1500
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
        invoice.setCurrency("STARS")
        invoice.setPrices(listOf(LabeledPrice(title, price)))
        bot.execute(invoice)
    }
}
