# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS build
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src src
# Optional managed-environment CA, consumed as a secret and removed in this layer.
RUN --mount=type=cache,target=/root/.m2,sharing=locked --mount=type=secret,id=proxy_ca --mount=type=secret,id=maven_settings \
    if [ -f /run/secrets/proxy_ca ]; then \
      cp /run/secrets/proxy_ca /tmp/seg07-ca.pem; \
      printf 'ca_certificate=/tmp/seg07-ca.pem\n' > /tmp/seg07-wgetrc; \
      export WGETRC=/tmp/seg07-wgetrc; \
      cp "$JAVA_HOME/lib/security/cacerts" /tmp/seg07-cacerts; \
      keytool -importcert -noprompt -alias seg07-proxy -file /run/secrets/proxy_ca -keystore /tmp/seg07-cacerts -storepass changeit; \
      export MAVEN_OPTS='-Djavax.net.ssl.trustStore=/tmp/seg07-cacerts -Djavax.net.ssl.trustStorePassword=changeit'; \
    fi; \
    if [ -f /run/secrets/maven_settings ]; then settings="-s /run/secrets/maven_settings"; fi; \
    chmod +x mvnw && ./mvnw -B ${settings:-} clean package -DskipTests; result=$?; \
    rm -f /tmp/seg07-ca.pem /tmp/seg07-wgetrc /tmp/seg07-cacerts; exit "$result"
FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
