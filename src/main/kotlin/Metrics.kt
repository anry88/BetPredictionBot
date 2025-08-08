import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

object Metrics {
    val commandCounter: Counter = Counter.build()
        .name("bot_commands_total")
        .help("Total number of bot commands received")
        .labelNames("command")
        .register()

    val jobOperationCounter: Counter = Counter.build()
        .name("bot_job_operations_total")
        .help("Total job operations")
        .labelNames("operation")
        .register()

    val refundOperationCounter: Counter = Counter.build()
        .name("bot_refund_operations_total")
        .help("Total refund-related operations")
        .labelNames("operation")
        .register()

    val totalUsersGauge: Gauge = Gauge.build()
        .name("bot_users_total")
        .help("Total number of users")
        .register()

    val activeUsersGauge: Gauge = Gauge.build()
        .name("bot_users_active_last_day")
        .help("Number of users active in the last 24 hours")
        .register()

    private var server: HTTPServer? = null

    fun startServer(port: Int) {
        if (server == null) {
            DefaultExports.initialize()
            server = HTTPServer(port)
        }
    }

    fun updateUserMetrics(total: Long, activeLastDay: Long) {
        totalUsersGauge.set(total.toDouble())
        activeUsersGauge.set(activeLastDay.toDouble())
    }
}
