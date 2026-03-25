package org.mavriksc.clearcast

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import java.io.File

fun Application.configureStatic() {
    routing {
        staticResources("/static", "static")
        staticFiles("/radar", File("responses/images"))
    }
}
