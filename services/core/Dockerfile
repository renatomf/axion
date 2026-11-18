# syntax=docker/dockerfile:1
# Container image of the core service. Build it from the repository root:
#   just image
#
# Two stages: `build` compiles the service with the full JDK; the final stage
# copies only the packaged JAR onto a minimal runtime image. The compiler,
# Gradle and the sources never reach the image that runs.
#
# Base images are pinned by digest; Dependabot bumps tag and digest together.

FROM eclipse-temurin:25-jdk-noble@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f AS build
WORKDIR /workspace
COPY . .
# Tests run in CI before an image is built, so this stage only packages the
# JAR. The cache mount keeps Gradle's downloads between builds without
# storing them in any image layer.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar \
 && cp build/libs/finaxis-core.jar /workspace/app.jar

# Distroless: the Java runtime and nothing else, no shell and no package
# manager, so an attacker who gets in finds no tools. The `nonroot` variant
# runs as user 65532, never as root.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:1c4ced761d17961c900cba60c33c8c3c46edbdadaa9d5ef9806eb5a6824fd406
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER nonroot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
