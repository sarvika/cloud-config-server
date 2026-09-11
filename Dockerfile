FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Cache dependencies separately from source so code changes don't bust this layer
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# This image ships no demo data and no default connector endpoints - it's generic across
# environments and services. GIT_REPO_URI, VAULT_HOST/PORT/SCHEME, and VAULT_TOKEN have
# no fallback and must be supplied at deploy time (see README's "Environment variables").
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8889
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
