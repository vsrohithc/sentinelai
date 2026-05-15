# ─── Stage 1: Build ───────────────────────────────────────────────────────────
# Use the official Maven image with Eclipse Temurin JDK 21 to compile and
# package the application. The builder stage is discarded after the fat JAR
# is extracted, so no build tooling ends up in the final image.
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy the POM first so that Docker can cache the dependency layer separately
# from the source code. Dependencies are only re-downloaded when pom.xml changes.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source tree and package, skipping tests (tests run in CI, not at image
# build time — they require a live database and mock servers).
COPY src ./src
RUN mvn package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
# Minimal JRE image — no compiler, no Maven, no shell tooling.
# Alpine base keeps the final image under 200 MB.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as a non-root user.
# The sentinel user is created without a home directory (-H) and without a
# login shell (-s /sbin/nologin) so the process cannot escalate privileges
# even if a vulnerability allows arbitrary command execution.
RUN addgroup -S sentinel && adduser -S -G sentinel -H -s /sbin/nologin sentinel

WORKDIR /app

# Copy only the executable JAR from the builder stage
COPY --from=builder /build/target/sentinelai-backend-*.jar app.jar

# Give the sentinel user read access to the JAR
RUN chown sentinel:sentinel app.jar

USER sentinel

# Port the Spring Boot server listens on inside the container.
# Map to a host port with -p <host>:<container> in docker run or docker-compose.
EXPOSE 8080

# Docker HEALTHCHECK: call the Actuator health endpoint every 30 s.
# The container is marked unhealthy if it fails 3 times in a row.
# This drives the depends_on health-check in docker-compose.prod.yml so
# the frontend container only starts once the backend is fully initialised.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# JVM flags:
#   -XX:+UseContainerSupport      — respect cgroup CPU/memory limits (default in JDK 21)
#   -XX:MaxRAMPercentage=75.0     — cap heap at 75 % of container memory
#   -Djava.security.egd=...       — faster SecureRandom on Linux (avoids /dev/random block)
#   -Dspring.profiles.active=prod — activate structured JSON logging (logback-spring.xml)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=prod", \
    "-jar", "app.jar"]
