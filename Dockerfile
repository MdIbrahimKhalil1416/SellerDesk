# syntax=docker/dockerfile:1
#
# SellerDesk as ONE image: the Spring Boot backend also serves the built React frontend.
# Build from the repository root:
#   docker build -t sellerdesk .
#
# Stage 1 builds the frontend with Node.
# Stage 2 copies that build into the backend's static resources and builds the jar.
# Stage 3 only contains a JRE and the jar: small, and no compiler or build tools in production.

# ---- Stage 1: frontend ----------------------------------------------------------------
# Node 24 = frontend/.node-version (same as CI).
FROM node:24-alpine AS frontend
WORKDIR /frontend

# Dependencies first: this layer is cached until package.json / package-lock.json change.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/ ./
RUN npm run build

# ---- Stage 2: backend jar -------------------------------------------------------------
# Java 21 = <java.version> in backend/pom.xml.
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /workspace

# Copy only what dependency resolution needs first, so dependencies are downloaded again only
# when pom.xml changes, not on every code change.
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q --no-transfer-progress dependency:go-offline

COPY backend/src/ src/
# The built React app becomes classpath:/static/ inside the jar (served by SpaWebConfig).
COPY --from=frontend /frontend/dist/ src/main/resources/static/
# Tests do not run here; CI runs them before it builds this image.
RUN ./mvnw -B -q --no-transfer-progress -DskipTests package \
    && cp target/sellerdesk-backend-*.jar app.jar

# ---- Stage 3: runtime -----------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Unprivileged user: if the app were ever compromised, the attacker is not root in the container.
RUN addgroup -S sellerdesk && adduser -S -G sellerdesk -H -s /sbin/nologin sellerdesk
WORKDIR /app
COPY --from=backend --chown=sellerdesk:sellerdesk /workspace/app.jar app.jar
USER sellerdesk

# Production profile by default (Swagger off, Secure cookies, health shows status only).
ENV SPRING_PROFILES_ACTIVE=prod
# Use up to 75% of the container's memory limit for the heap (Render free = 512 MB).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The app listens on $PORT (server.port: ${PORT:8080} in application.yml). Render sets PORT;
# without it the app uses 8080. EXPOSE is documentation only.
EXPOSE 8080

# Exec form: java is PID 1 and receives SIGTERM directly, so Spring shuts down gracefully.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
