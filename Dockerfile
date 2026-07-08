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
# Run as a non-root user (defense in depth: a container breakout can't land as root).
RUN addgroup -S spring && adduser -S spring -G spring \
    && chown spring:spring /app/app.jar
USER spring
# Matches server.port in application.properties.
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
