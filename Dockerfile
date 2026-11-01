FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/
COPY docs/screenshots/ docs/screenshots/
RUN ./gradlew installDist --no-daemon

FROM caddy:2.10.2-alpine AS proxy
# High listener ports do not need the upstream binary's privileged-port capability.
RUN setcap -r /usr/bin/caddy
USER 10001:10001

FROM eclipse-temurin:21-jre-jammy AS web
RUN groupadd --gid 10001 ledger && useradd --uid 10001 --gid ledger --no-create-home ledger
WORKDIR /app
COPY --from=build /build/build/install/pam-ledger/lib/ ./lib/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-cp", "/app/lib/*", "com.pam.ledger.web.WebAppKt"]
CMD ["--host", "0.0.0.0", "--port", "8080", "--data", "/data/ledger"]
