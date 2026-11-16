FROM eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS build

WORKDIR /workspace
COPY . .

RUN chmod +x mvnw \
    && ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515 AS runtime

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar app.jar

RUN mkdir -p /var/lib/m2/attachments \
    && chown -R 10001:10001 /var/lib/m2/attachments

EXPOSE 8080

USER 10001:10001

ENTRYPOINT ["java", "-jar", "app.jar"]
