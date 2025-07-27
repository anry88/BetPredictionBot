package service

import Config
import FootballBot
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import repository.SubscriptionPlan

class StarsPaymentService(private val bot: FootballBot) {
    private val providerToken: String =
        Config.getProperty("provider.token") ?: "stars"

    private val prices = mapOf(
        SubscriptionPlan.BOT_1 to 1,
        SubscriptionPlan.BOT_3 to 1,
        SubscriptionPlan.CHANNEL_1 to 1,
        SubscriptionPlan.CHANNEL_3 to 1
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
}
