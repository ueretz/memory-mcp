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
RUN java -Djarmode=tools -jar app.jar extract --destination /app/extracted \
    && apt-get update \
    && java -cp "/app/extracted/lib/*" \
         com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf /app/extracted /var/lib/apt/lists/*

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
