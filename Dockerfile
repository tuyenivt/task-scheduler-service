# syntax=docker/dockerfile:1.7
# Multi-stage build for the task-scheduler-service.
# Stage 1 builds and extracts Spring Boot's layered jar so each layer can be
# cached independently in subsequent rebuilds.
# Stage 2 is a slim JRE-only runtime image.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Gradle wrapper and build inputs first so dependency resolution is cached
# independently of source changes.
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

# Spring Boot 3.x produces a layered jar by default; split it so the runtime
# image can copy each layer to its own filesystem layer.
# Using the Boot 3.5+ `jarmode=tools` form (the older `layertools` was deprecated).
RUN mkdir -p /workspace/extracted && \
    java -Djarmode=tools -jar /workspace/build/libs/*.jar extract --layers --destination /workspace/extracted

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as non-root.
RUN groupadd --system app && useradd --system --gid app --home /app app
USER app

# Layers ordered from least-frequently changed to most-frequently changed so
# Docker can reuse cached layers across rebuilds.
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

EXPOSE 8080

# Container-aware JVM defaults; UseG1GC is the Boot 3 default but stated
# explicitly so changes are visible at deploy time.
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
