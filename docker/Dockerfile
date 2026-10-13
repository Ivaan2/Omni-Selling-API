# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Optional build secrets support restricted corporate/cloud networks without
# embedding proxy credentials or custom trust stores in image layers.
RUN --mount=type=secret,id=maven-settings,target=/root/.m2/settings.xml \
    --mount=type=secret,id=java-truststore <<'SH'
set -eu
if [ -f /run/secrets/java-truststore ]; then
  export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/run/secrets/java-truststore"
fi
mvn -B dependency:go-offline
SH
COPY src src
RUN --mount=type=secret,id=maven-settings,target=/root/.m2/settings.xml \
    --mount=type=secret,id=java-truststore <<'SH'
set -eu
if [ -f /run/secrets/java-truststore ]; then
  export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/run/secrets/java-truststore"
fi
mvn -B -DskipTests package
SH
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /build/target/order-management-1.0.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
