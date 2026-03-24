package org.mavriksc.clearcast

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun loadEnv(): Dotenv {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val envFile = dir.resolve(".env")
        if (Files.exists(envFile)) {
            return dotenv {
                directory = dir.toString()
                filename = ".env"
                ignoreIfMissing = true
            }
        }

        val gradleFile = dir.resolve("build.gradle.kts")
        if (Files.exists(gradleFile)) {
            return dotenv {
                directory = dir.toString()
                filename = ".env"
                ignoreIfMissing = true
            }
        }

        dir = dir.parent
    }

    return dotenv { ignoreIfMissing = true }
}
