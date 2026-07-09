FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Matches server.port in application.properties.
EXPOSE 8081
# Readiness reflects DB connectivity; returns 503 (→ non-zero wget exit) until the app can serve.
# Uses BusyBox wget (present in the alpine base) so no extra packages are needed.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
