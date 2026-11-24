package dev.axion.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The core's packaged JAR, run as a process of its own that a test can kill and start again: the
 * process the fault-injection suite stops abruptly, as a crash or an evicted container would, which
 * a context inside the test's JVM cannot be. It runs with the {@code local} profile, the only one
 * whose webhooks may reach a receiver on this machine, and each start logs to a file of its own.
 *
 * <p>The JAR's path is the system property {@value #JAR_PROPERTY}, which the {@code
 * faultInjectionTest} task sets after building it.
 */
public final class CoreProcess implements AutoCloseable {

    static final String JAR_PROPERTY = "axion.core.jar";

    private static final Duration STARTUP = Duration.ofMinutes(2);

    private final List<String> command;
    private final Path directory;
    private final URI base;
    private final HttpClient http = HttpClient.newHttpClient();
    private int starts;
    private @Nullable Process process;

    private CoreProcess(List<String> command, Path directory, URI base) {
        this.command = command;
        this.directory = directory;
        this.base = base;
    }

    /**
     * A core listening on the port, with these settings as command-line arguments, which take
     * precedence over every other source; not started yet.
     */
    public static CoreProcess of(int port, Map<String, String> settings) {
        String jar = Objects.requireNonNull(
                System.getProperty(JAR_PROPERTY),
                "Run the suite with ./gradlew faultInjectionTest, which sets " + JAR_PROPERTY);
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = new ArrayList<>(List.of(java, "-jar", jar, "--spring.profiles.active=local"));
        command.add("--server.port=" + port);
        settings.forEach((name, value) -> command.add("--" + name + "=" + value));
        // The local profile reads a developer's .env from ../../infra/compose; from here it finds none.
        Path directory = Path.of(jar)
                .toAbsolutePath()
                .resolveSibling("../fault-injection")
                .normalize();
        return new CoreProcess(List.copyOf(command), directory, URI.create("http://localhost:" + port));
    }

    /** Starts the process and waits until the core is ready to serve. */
    public void start() {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("The core is already running");
        }
        starts++;
        Path log = directory.resolve("core-" + starts + ".log");
        try {
            Files.createDirectories(directory);
            process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        awaitReady(process, log);
    }

    /** Kills the process at once, with no shutdown of any kind, and waits until it is gone. */
    public void kill() {
        Process running = Objects.requireNonNull(process, "The core was never started");
        running.destroyForcibly();
        try {
            running.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The core's address, for requests to its API. */
    public URI uri(String path) {
        return base.resolve(path);
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            kill();
        }
    }

    private void awaitReady(Process running, Path log) {
        Instant deadline = Instant.now().plus(STARTUP);
        var readiness = HttpRequest.newBuilder(uri("/actuator/health/readiness"))
                .timeout(Duration.ofSeconds(2))
                .build();
        while (Instant.now().isBefore(deadline)) {
            if (!running.isAlive()) {
                throw new IllegalStateException(
                        "The core exited with " + running.exitValue() + " while starting; see " + log);
            }
            try {
                if (http.send(readiness, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException notListeningYet) {
                // Not listening yet: try again.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            pause();
        }
        running.destroyForcibly();
        throw new IllegalStateException("The core was not ready after " + STARTUP + "; see " + log);
    }

    private static void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
