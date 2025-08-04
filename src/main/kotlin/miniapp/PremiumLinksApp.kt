package miniapp

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.html.*
import service.DatabaseService
import java.time.Instant
import java.time.ZoneId

fun startPremiumLinksServer() {
    embeddedServer(Netty, port = 6006) {
        routing {
            get("/") {
                val links = DatabaseService.invites.getActiveInviteLinksWithRemainingSlots()
                call.respondHtml {
                    head {
                        script(src = "https://telegram.org/js/telegram-web-app.js") {}
                        style {
                            unsafe {
                                raw(
                                    """
                                    body {
                                        background-color: var(--tg-theme-bg-color);
                                        color: var(--tg-theme-text-color);
                                        font-family: sans-serif;
                                        margin: 0;
                                        padding: 1rem;
                                    }
                                    h1 { text-align: center; }
                                    .container { max-width: 600px; margin: 0 auto; }
                                    .link-card {
                                        background: var(--tg-theme-secondary-bg-color);
                                        border-radius: 10px;
                                        padding: 1rem;
                                        margin-bottom: 1rem;
                                        box-shadow: 0 1px 2px rgba(0,0,0,0.1);
                                    }
                                    .link-info {
                                        color: var(--tg-theme-hint-color);
                                        margin-bottom: 0.75rem;
                                        font-size: 0.9rem;
                                    }
                                    .link-btn {
                                        display: block;
                                        width: 100%;
                                        padding: 0.75rem;
                                        text-align: center;
                                        text-decoration: none;
                                        border-radius: 8px;
                                        background-color: var(--tg-theme-button-color);
                                        color: var(--tg-theme-button-text-color);
                                        font-weight: bold;
                                    }
                                    """.trimIndent()
                                )
                            }
                        }
                    }
                    body {
                        div(classes = "container") {
                            h1 { +"Available premium channel links" }
                            if (links.isEmpty()) {
                                p { +"No available free premium channel joining links at the moment." }
                            } else {
                                links.forEach { (link, left, expires) ->
                                    val date = Instant.ofEpochSecond(expires)
                                        .atZone(ZoneId.of("UTC"))
                                        .toLocalDate()
                                    div(classes = "link-card") {
                                        div(classes = "link-info") {
                                            +"$left slots left • valid until $date"
                                        }
                                        a(href = link, classes = "link-btn") { +"Join" }
                                    }
                                }
                            }
                        }
                        script {
                            unsafe { raw("Telegram.WebApp.ready();") }
                        }
                    }
                }
            }
        }
    }.start(wait = false)
}

