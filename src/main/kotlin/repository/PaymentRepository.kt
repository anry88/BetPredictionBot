package repository

import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment
import java.sql.Connection
import java.sql.DriverManager

data class Payment(
    val id: Long,
    val userId: String,
    val telegramPaymentChargeId: String,
    val providerPaymentChargeId: String?,
    val payload: String?,
    val currency: String,
    val amount: Int,
    val createdAt: Long
)

class PaymentRepository {
    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:predictions.db")

    fun addPayment(userId: String, payment: SuccessfulPayment): Long {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "INSERT INTO payments (user_id, telegram_payment_charge_id, provider_payment_charge_id, payload, currency, amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        )
        stmt.setString(1, userId)
        stmt.setString(2, payment.telegramPaymentChargeId)
        stmt.setString(3, payment.providerPaymentChargeId)
        stmt.setString(4, payment.invoicePayload)
        stmt.setString(5, payment.currency)
        stmt.setInt(6, payment.totalAmount)
        stmt.setLong(7, System.currentTimeMillis() / 1000)
        stmt.executeUpdate()
        val rs = stmt.generatedKeys
        val id = if (rs.next()) rs.getLong(1) else 0L
        rs.close()
        stmt.close()
        connection.close()
        return id
    }

    fun getLastPayment(userId: String): Payment? {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "SELECT id, user_id, telegram_payment_charge_id, provider_payment_charge_id, payload, currency, amount, created_at FROM payments WHERE user_id = ? ORDER BY created_at DESC LIMIT 1"
        )
        stmt.setString(1, userId)
        val rs = stmt.executeQuery()
        val payment = if (rs.next()) {
            Payment(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("telegram_payment_charge_id"),
                rs.getString("provider_payment_charge_id"),
                rs.getString("payload"),
                rs.getString("currency"),
                rs.getInt("amount"),
                rs.getLong("created_at")
            )
        } else {
            null
        }
        rs.close()
        stmt.close()
        connection.close()
        return payment
    }

    fun getPayment(id: Long): Payment? {
        val connection = getConnection()
        val stmt = connection.prepareStatement(
            "SELECT id, user_id, telegram_payment_charge_id, provider_payment_charge_id, payload, currency, amount, created_at FROM payments WHERE id = ?"
        )
        stmt.setLong(1, id)
        val rs = stmt.executeQuery()
        val payment = if (rs.next()) {
            Payment(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("telegram_payment_charge_id"),
                rs.getString("provider_payment_charge_id"),
                rs.getString("payload"),
                rs.getString("currency"),
                rs.getInt("amount"),
                rs.getLong("created_at")
            )
        } else {
            null
        }
        rs.close()
        stmt.close()
        connection.close()
        return payment
    }
}

