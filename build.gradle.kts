// Archivo: build.gradle.kts (Nivel de Proyecto, NO el de app)

plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.1" apply false

    // AÑADE ESTA LÍNEA (El plugin que falta)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}