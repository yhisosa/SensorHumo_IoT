// Archivo: settings.gradle.kts

pluginManagement {
    repositories {
        google() // Repositorio de Google (para plugins de Android)
        mavenCentral() // Repositorio central
        gradlePluginPortal() // Portal de plugins de Gradle
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google() // Repositorio de Google (para librerías)
        mavenCentral() // Repositorio central
    }
}

rootProject.name = "SensorHumo_IoT"
include(":app")