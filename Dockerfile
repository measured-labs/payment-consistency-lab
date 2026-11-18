# 앱 이미지 — 빌드까지 컨테이너 안에서 한다. 빌드 파일을 먼저 넣어 의존성 층을 캐시한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY mock-pg/build.gradle.kts mock-pg/
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon -q :bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/payment-consistency-lab-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8102
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
