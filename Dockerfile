# --- Build stage ---
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app

# Resolve dependencies in a separate layer so source changes do not re-download them
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /app/target/*.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8082

# application-docker.properties supplies container-network defaults
ENV SPRING_PROFILES_ACTIVE=docker

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
