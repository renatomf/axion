// Build script for the core service: https://docs.gradle.org/current/userguide/writing_build_scripts.html
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    // Adds `bootRun` and packages the service as an executable JAR (`bootJar`).
    alias(libs.plugins.spring.boot)
    // Formats the code; `spotlessCheck` runs as part of `check` (and so `build`).
    alias(libs.plugins.spotless)
    // Runs Error Prone checks while javac compiles main and test code.
    alias(libs.plugins.errorprone)
}

java {
    // Compile and test with the JDK version from the version catalog. Gradle
    // fails the build if that JDK is not installed, instead of silently
    // using whatever `java` is on the PATH.
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

spotless {
    java {
        // One formatter for all Java code, so formatting is never discussed in
        // review. Palantir Java Format: 4-space indentation, 120 columns.
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        // Imports: no unused ones, sorted the same way everywhere.
        removeUnusedImports()
    }
}

dependencies {
    // Import Spring Boot's bill of materials as a Gradle platform, so the
    // libraries below take their versions from it.
    implementation(platform(libs.spring.boot.dependencies))
    // The same for Spring Modulith: its modules below take their versions from it.
    implementation(platform(libs.spring.modulith.bom))

    // Spring MVC on an embedded Tomcat server, plus Spring core,
    // auto-configuration and logging (Logback via SLF4J).
    implementation(libs.spring.boot.starter.webmvc)
    // Operational endpoints under /actuator, starting with health.
    implementation(libs.spring.boot.starter.actuator)
    // Authentication and authorization for every HTTP request, as servlet filters.
    implementation(libs.spring.boot.starter.security)
    // Database access over JDBC, with a connection pool.
    implementation(libs.spring.boot.starter.jdbc)
    // The PostgreSQL driver is needed only when the service runs, never to compile.
    runtimeOnly(libs.postgresql)
    // Schema migrations from src/main/resources/db/migration, run at startup.
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    // Module annotations; they only describe the structure and add no runtime behavior.
    implementation(libs.spring.modulith.api)
    // Annotations that state, in the code, which references may be null.
    implementation(libs.jspecify)

    // Test-only libraries: on the test classpath, never in the packaged JAR.
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Error Prone itself: on its own configuration, used only by the compiler.
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
}

tasks.withType<JavaCompile>().configureEach {
    // Zero warnings: enable every javac lint and treat any warning, from javac
    // or Error Prone, as an error. A warning nobody fixes is noise that hides
    // the next one.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))

    options.errorprone {
        // A possible null dereference is a compile error, not a warning.
        check("NullAway", CheckSeverity.ERROR)
        // Check every class under dev.finaxis, even in a package that forgot
        // its @NullMarked package-info.
        option("NullAway:AnnotatedPackages", "dev.finaxis")
        // Follow the JSpecify specification, including generics such as
        // List<@Nullable String>.
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.bootRun {
    // `./gradlew bootRun` is how developers start the service locally, so it
    // activates the `local` profile (src/main/resources/application-local.yml).
    // The packaged JAR runs with the default profile unless told otherwise.
    systemProperty("spring.profiles.active", "local")
}

tasks.test {
    // Run tests with the JUnit Platform (JUnit Jupiter). Without this line
    // Gradle looks for JUnit 4 tests and would find none.
    useJUnitPlatform()
}
