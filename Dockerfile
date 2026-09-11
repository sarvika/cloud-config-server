FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Copy just the build config first so dependency resolution is cached in its own Docker
# layer and only re-runs when the Gradle build files actually change, not on every
# source edit.
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# This image ships no demo data and no default connector endpoints - it's generic across
# environments and services. GIT_REPO_URI, VAULT_HOST/PORT/SCHEME, and VAULT_TOKEN have
# no fallback and must be supplied at deploy time (see README's "Environment variables").
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8889
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
