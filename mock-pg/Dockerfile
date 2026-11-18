# 흉내 PG 이미지 — 빌드 컨텍스트는 repo 루트(docker-compose.yml)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY mock-pg/build.gradle.kts mock-pg/
RUN ./gradlew --no-daemon -q :mock-pg:dependencies > /dev/null
COPY mock-pg/src/main mock-pg/src/main
RUN ./gradlew --no-daemon -q :mock-pg:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/mock-pg/build/libs/mock-pg-0.0.1-SNAPSHOT.jar mock-pg.jar
EXPOSE 8110
ENTRYPOINT ["java", "-jar", "/app/mock-pg.jar"]
