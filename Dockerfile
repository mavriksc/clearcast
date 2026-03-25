FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

RUN ./gradlew --no-daemon dependencies

COPY src src

RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/install/clearcast/ /app/

EXPOSE 8080

ENTRYPOINT ["/app/bin/clearcast"]
