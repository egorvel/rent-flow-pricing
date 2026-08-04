# syntax=docker/dockerfile:1

FROM eclipse-temurin:25.0.3_9-jdk-noble AS build

WORKDIR /workspace

RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

COPY --chmod=0755 mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml

RUN ./mvnw -B -ntp dependency:go-offline

COPY src src

RUN ./mvnw -B -ntp package

FROM eclipse-temurin:25.0.3_9-jre-noble

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 pricing \
    && useradd \
        --uid 10001 \
        --gid 10001 \
        --home-dir /nonexistent \
        --shell /usr/sbin/nologin \
        --no-create-home \
        pricing \
    && install --directory --owner=10001 --group=10001 /opt/pricing

WORKDIR /opt/pricing

COPY --from=build --chown=10001:10001 /workspace/target/pricing.jar /opt/pricing/pricing.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=5 \
    CMD ["curl", "--fail", "--silent", "--show-error", "--max-time", "2", "http://localhost:8080/readyz"]

ENTRYPOINT ["java", "-jar", "/opt/pricing/pricing.jar"]
