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

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
