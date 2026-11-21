# syntax=docker/dockerfile:1
# Container image of the rails simulator. Build it from the repository root:
#   just image rails-simulator
#
# The same two stages as the core's image: `build` compiles the service with
# the full JDK; the final stage copies only the packaged application, split
# into layers, onto a minimal runtime image.
#
# Base images are pinned by digest; Dependabot bumps tag and digest together.

FROM eclipse-temurin:25-jdk-noble@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f AS build
WORKDIR /workspace
COPY . .
# Tests run in CI before an image is built, so this stage only packages the
# JAR. The cache mount keeps Gradle's downloads out of every image layer.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar
# One directory per layer, ordered by how often it changes, so a change to
# the simulator's code ships only the last, small layer.
RUN java -Djarmode=tools -jar build/libs/finaxis-rails-simulator.jar \
    extract --layers --destination extracted

# Distroless, `nonroot` variant: the Java runtime only, running as user 65532.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:1c4ced761d17961c900cba60c33c8c3c46edbdadaa9d5ef9806eb5a6824fd406
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER nonroot
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "finaxis-rails-simulator.jar"]
