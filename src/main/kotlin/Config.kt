import java.io.FileInputStream
import java.util.Properties

object Config {
    private val properties: Properties = Properties()

    init {
        FileInputStream("config.properties").use { properties.load(it) }
    }

    fun getProperty(key: String): String? {
        return properties.getProperty(key)
    }
}
