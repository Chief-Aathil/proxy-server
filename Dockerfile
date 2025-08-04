# Dockerfile for Proxy-Server (Gradle)

# --- Build Stage ---
FROM eclipse-temurin:17-jdk-focal AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Copy source code
COPY src ./src

# Make gradlew executable
RUN chmod +x gradlew

# Build the Spring Boot application using Gradle
# The 'bootJar' task creates an executable JAR
RUN ./gradlew bootJar -x test

# --- Run Stage ---
FROM eclipse-temurin:17-jre-focal

WORKDIR /app

# Copy the built JAR from the build stage
# Gradle's bootJar typically puts it in build/libs/
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose the port where the server proxy listens for client connections
EXPOSE 9090

# Run the Spring Boot application
# Use environment variables or bind-mount application.properties for external configuration
ENTRYPOINT ["java", "-jar", "app.jar"]

# Example of how you might pass properties at runtime (e.g., in docker-compose.yml)
# -Dproxy.server.listen-port=9090
