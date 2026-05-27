import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @BeforeTest
    fun setupConfig() {
        File("config.properties").writeText(
            """
            test=true
            local.model.port=7011
            """.trimIndent()
        )
        Config.reload()
    }

    @AfterTest
    fun cleanupConfig() {
        File("config.properties").delete()
    }

    @Test
    fun localModelBaseUrlUsesConfiguredPort() {
        assertEquals(7011, Config.getLocalModelPort())
        assertEquals("http://localhost:7011", Config.getLocalModelBaseUrl())
    }

    @Test
    fun modelDataUploadRunsNightlyOnlyOutsideTestEnvironment() {
        assertEquals("0 0 3 * * ?", Config.getUploadModelDataCron())
        assertEquals(false, Config.isModelDataUploadEnabled())
        assertEquals(true, Config.isModelDataUploadEnabledFor(false))
    }
}
