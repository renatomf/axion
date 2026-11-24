# syntax=docker/dockerfile:1
# Container image of the core service. Build it from the repository root:
#   just image
#
# Two stages: `build` compiles the service with the full JDK; the final stage
# copies only the packaged application onto a minimal runtime image. The
# compiler, Gradle and the sources never reach the image that runs.
#
# Base images are pinned by digest; Dependabot bumps tag and digest together.

FROM eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS build
WORKDIR /workspace
COPY . .
# Tests run in CI before an image is built, so this stage only packages the
# JAR. The cache mount keeps Gradle's downloads between builds without
# storing them in any image layer.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar
# Spring Boot's layertools split the JAR into directories ordered by how often
# they change: third-party libraries, Spring Boot's loader, snapshot
# libraries, then the service's own code. Each becomes one image layer below,
# so a change to the service's code rebuilds and ships only the last, small
# layer and reuses the others from the cache.
RUN java -Djarmode=tools -jar build/libs/axion-core.jar \
    extract --layers --destination extracted

# Distroless: the Java runtime and nothing else, no shell and no package
# manager, so an attacker who gets in finds no tools. The `nonroot` variant
# runs as user 65532, never as root.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:59bbb349c9752fc57b75744b41b1614c9dabe8bb4869523aabd5a4288f6c09a0
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER nonroot
EXPOSE 8080
# The extracted JAR finds its libraries in lib/ through its manifest.
ENTRYPOINT ["java", "-jar", "axion-core.jar"]
