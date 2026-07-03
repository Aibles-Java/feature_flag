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
# The image is the production artifact: default to the prod profile so a container
# started without APP_JWT_SECRET / SPRING_DATASOURCE_* fails fast instead of silently
# running on the committed local-dev defaults. Override explicitly for local use.
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
