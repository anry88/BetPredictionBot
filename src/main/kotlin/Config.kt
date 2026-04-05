import java.io.FileInputStream
import java.util.Properties

object Config {
    private const val DEFAULT_LOCAL_MODEL_PORT = 7007
    private const val PROD_UPLOAD_MODEL_DATA_CRON = "0 0 3 ? * MON,WED,FRI"
    private const val TEST_UPLOAD_MODEL_DATA_CRON = "0 0 3 ? * TUE,THU,SAT"

    private val properties: Properties = Properties()

    init {
        reload()
    }

    fun reload() {
        properties.clear()
        FileInputStream("config.properties").use { properties.load(it) }
    }

    fun getProperty(key: String): String? {
        return properties.getProperty(key)
    }

    fun getBooleanProperty(key: String, default: Boolean = false): Boolean {
        return properties.getProperty(key)?.toBoolean() ?: default
    }

    fun getIntProperty(key: String, default: Int): Int {
        return properties.getProperty(key)?.toIntOrNull() ?: default
    }

    fun isTestEnvironment(): Boolean {
        return getBooleanProperty("test")
    }

    fun getLocalModelPort(): Int {
        return getIntProperty("local.model.port", DEFAULT_LOCAL_MODEL_PORT)
    }

    fun getLocalModelBaseUrl(): String {
        return "http://localhost:${getLocalModelPort()}"
    }

    fun getUploadModelDataCron(): String {
        return uploadModelDataCronFor(isTestEnvironment())
    }

    fun uploadModelDataCronFor(isTestEnvironment: Boolean): String {
        return if (isTestEnvironment) TEST_UPLOAD_MODEL_DATA_CRON else PROD_UPLOAD_MODEL_DATA_CRON
    }
}
