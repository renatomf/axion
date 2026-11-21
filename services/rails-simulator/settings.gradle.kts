// Settings for the rails simulator build: https://docs.gradle.org/current/userguide/settings_file_basics.html
rootProject.name = "finaxis-rails-simulator"

// Where Gradle looks for build plugins.
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

// Where Gradle looks for libraries. Repositories are declared only here:
// a build script that declares its own repository fails the build, so
// every dependency comes from a known source.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
