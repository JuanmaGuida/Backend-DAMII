FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .

RUN chmod +x mvnw \
    && ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar app.jar

EXPOSE 8080

USER 10001:10001

ENTRYPOINT ["java", "-jar", "app.jar"]
