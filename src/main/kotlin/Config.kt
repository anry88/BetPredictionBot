import java.io.FileInputStream
import java.util.Properties

object Config {
    private const val DEFAULT_LOCAL_MODEL_PORT = 7008
    private const val PROD_UPLOAD_MODEL_DATA_CRON = "0 0 3 * * ?"

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
        return nonBlank(System.getenv("LOCAL_MODEL_BASE_URL"))
            ?: nonBlank(getProperty("local.model.base.url"))
            ?: "http://localhost:${getLocalModelPort()}"
    }

    private fun nonBlank(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun getUploadModelDataCron(): String {
        return PROD_UPLOAD_MODEL_DATA_CRON
    }

    fun isModelDataUploadEnabled(): Boolean {
        return isModelDataUploadEnabledFor(isTestEnvironment())
    }

    fun isModelDataUploadEnabledFor(isTestEnvironment: Boolean): Boolean {
        return !isTestEnvironment
    }
}
