# syntax=docker/dockerfile:1
#
# SellerDesk backend image. Build from the backend/ folder:
#   docker build -t sellerdesk-backend backend
#
# Stage 1 builds the jar with the full JDK + Maven wrapper.
# Stage 2 only contains a JRE and the jar: smaller, and no compiler or build tools in production.

# ---- Stage 1: build -------------------------------------------------------------------
# Java 21 = <java.version> in pom.xml.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Copy only what dependency resolution needs first. Docker caches this layer, so dependencies
# are downloaded again only when pom.xml changes, not on every code change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q --no-transfer-progress dependency:go-offline

COPY src/ src/
# Tests do not run here; CI runs them separately before the image is built.
RUN ./mvnw -B -q --no-transfer-progress -DskipTests package \
    && cp target/sellerdesk-backend-*.jar app.jar

# ---- Stage 2: run ---------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Unprivileged user: if the app were ever compromised, the attacker is not root in the container.
RUN addgroup -S sellerdesk && adduser -S -G sellerdesk -H -s /sbin/nologin sellerdesk
WORKDIR /app
COPY --from=build --chown=sellerdesk:sellerdesk /workspace/app.jar app.jar
USER sellerdesk

# Production profile by default (Swagger off, Secure cookies, no health details).
# The hosting platform may override it.
ENV SPRING_PROFILES_ACTIVE=prod
# Use up to 75% of the container's memory limit for the heap (Render free = 512 MB).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The app listens on $PORT (server.port: ${PORT:8080} in application.yml). Render sets PORT;
# without it the app uses 8080. EXPOSE is documentation only.
EXPOSE 8080

# Exec form: java is PID 1 and receives SIGTERM directly, so Spring shuts down gracefully.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
