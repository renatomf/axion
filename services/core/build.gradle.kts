// Build script for the core service: https://docs.gradle.org/current/userguide/writing_build_scripts.html
plugins {
    java
}

java {
    // Compile and test with the JDK version from the version catalog. Gradle
    // fails the build if that JDK is not installed, instead of silently
    // using whatever `java` is on the PATH.
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

dependencies {
    // Import Spring Boot's bill of materials as a Gradle platform, so the
    // libraries below take their versions from it.
    implementation(platform(libs.spring.boot.dependencies))

    // Spring core, auto-configuration and logging (Logback via SLF4J).
    implementation(libs.spring.boot.starter)
}
