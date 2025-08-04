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
                                        padding: 1rem;
                                    }
                                    ul { list-style: none; padding: 0; }
                                    .link-btn {
                                        display: block;
                                        margin: 0.5rem 0;
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
                        h1 { +"Available premium channel links" }
                        if (links.isEmpty()) {
                            p { +"No available free premium channel joining links at the moment." }
                        } else {
                            ul {
                                links.forEachIndexed { index, (link, left, expires) ->
                                    val date = Instant.ofEpochSecond(expires)
                                        .atZone(ZoneId.of("UTC"))
                                        .toLocalDate()
                                    li {
                                        a(href = link, classes = "link-btn") {
                                            +"Link ${index + 1} - $left slots left (valid until $date)"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.start(wait = false)
}
