package org.mavriksc.clearcast

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

fun Application.configureStatic() {
    routing {
        staticResources("/static", "static")
    }
}
