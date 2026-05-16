# ── Stage 1: Build ─────────────────────────────────────────────────────────
# Use Maven + Java 21 to build the fat JAR
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml first — lets Docker cache dependencies layer
# Only re-downloads dependencies if pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B --no-transfer-progress

# ── Stage 2: Run ───────────────────────────────────────────────────────────
# Minimal JRE image — much smaller than full JDK
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create non-root user for security
RUN groupadd -r chainpulse && useradd -r -g chainpulse chainpulse

# Copy only the fat JAR from builder stage
COPY --from=builder /app/target/ChainPulse-0.0.1-SNAPSHOT.jar app.jar

# Change ownership
RUN chown chainpulse:chainpulse app.jar

USER chainpulse

# Expose port
EXPOSE 8080

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Run the app
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]