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
                    body {
                        h1 { +"Available premium channel links" }
                        if (links.isEmpty()) {
                            p { +"No available free premium channel joining links at the moment." }
                        } else {
                            ul {
                                for ((link, left, expires) in links) {
                                    val date = Instant.ofEpochSecond(expires)
                                        .atZone(ZoneId.of("UTC"))
                                        .toLocalDate()
                                    li { +"$link - $left slots left (valid until $date)" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.start(wait = false)
}
