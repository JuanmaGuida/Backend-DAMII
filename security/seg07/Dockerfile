# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src src
# Bootstrap the same Maven release as the Wrapper, with Apache's published SHA-512.
RUN --mount=type=cache,target=/root/.m2,sharing=locked --mount=type=secret,id=proxy_ca --mount=type=secret,id=maven_settings \
    set -eu; \
    trap 'rm -f /tmp/seg07-ca.pem /tmp/seg07-wgetrc /tmp/seg07-cacerts /tmp/seg07-maven.tar.gz' EXIT; \
    if [ -f /run/secrets/proxy_ca ]; then \
      cp /run/secrets/proxy_ca /tmp/seg07-ca.pem; \
      printf 'ca_certificate=/tmp/seg07-ca.pem\n' > /tmp/seg07-wgetrc; \
      export WGETRC=/tmp/seg07-wgetrc; \
      cp "$JAVA_HOME/lib/security/cacerts" /tmp/seg07-cacerts; \
      keytool -importcert -noprompt -alias seg07-proxy -file /run/secrets/proxy_ca -keystore /tmp/seg07-cacerts -storepass changeit; \
      export MAVEN_OPTS='-Djavax.net.ssl.trustStore=/tmp/seg07-cacerts -Djavax.net.ssl.trustStorePassword=changeit'; \
    fi; \
    maven_version=3.10.1; \
    maven_sha512=8af2fcc48ee6b8f8fc0b21054226e14404b88500df6483cdeb3ae603408f6de27b1397ac591a9fb0d98fee64f4f624fee1383efd25b8998786cda1598fb9ea39; \
    wget --no-verbose --https-only --tries=4 --timeout=30 --waitretry=2 --retry-connrefused \
      --retry-on-http-error=408,429,500,502,503,504 -O /tmp/seg07-maven.tar.gz \
      "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${maven_version}/apache-maven-${maven_version}-bin.tar.gz"; \
    printf '%s  %s\n' "$maven_sha512" /tmp/seg07-maven.tar.gz | sha512sum --check --strict; \
    tar -xzf /tmp/seg07-maven.tar.gz -C /opt; \
    set --; \
    if [ -f /run/secrets/maven_settings ]; then set -- -s /run/secrets/maven_settings; fi; \
    "/opt/apache-maven-${maven_version}/bin/mvn" -B "$@" clean package -DskipTests
FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
