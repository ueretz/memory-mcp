# syntax=docker/dockerfile:1

# ---- UI build stage ----
FROM node:24-alpine AS ui
WORKDIR /ui

# Dependencies change far less often than the dashboard source.
COPY ui/package.json ui/package-lock.json ./
RUN npm ci

COPY ui ./
# vite.config.ts writes the bundle to ../build/ui-dist, i.e. /build/ui-dist here.
RUN npm run build

# ---- Build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Cache Gradle dependency resolution separately from source changes.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew --version --no-daemon

COPY src src
COPY --from=ui /build/ui-dist build/ui-dist
# -PskipUi: the dashboard bundle is already built above, no npm in this image.
RUN ./gradlew bootJar --no-daemon -x test -PskipUi

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/memory-mcp.jar app.jar

# PDF export needs headless Chromium. `jarmode=tools extract` unpacks the fat jar so Playwright's
# bundled installer CLI can run directly (no separate Gradle/JDK needed here) - Spring Boot 4's
# extractor lays dependency jars flat under lib/ (not the old BOOT-INF/lib), so that's the whole
# classpath the CLI needs. `--with-deps` also apt-installs whatever OS packages *this* base image
# needs to actually launch Chromium, so the list doesn't have to be hand-maintained.
#
# Installing only "chromium" here (the one browser we actually launch) used to leave the app
# downloading Firefox and WebKit from the network on its *first* PDF request instead: Java's
# Playwright.create() unconditionally checks that all three default browsers are present and
# fetches whatever's missing, regardless of which one the code goes on to launch - so a request
# that should take a second instead hung for several minutes. Installing the full default set
# up front means that check always finds everything already there and never reaches the network;
# PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD is a second guard so a future dependency bump that adds a new
# required browser fails loudly (missing executable) instead of silently blocking a request again.
RUN java -Djarmode=tools -jar app.jar extract --destination /app/extracted \
    && apt-get update \
    && java -cp "/app/extracted/lib/*" \
         com.microsoft.playwright.CLI install --with-deps \
    && rm -rf /app/extracted /var/lib/apt/lists/*

ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
