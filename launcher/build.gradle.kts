plugins {
    // Kotlin is provided by AGP 9's built-in Kotlin support (KGP pinned by AGP),
    // so we no longer apply org.jetbrains.kotlin.android ourselves.
    id("com.android.application") version "9.2.0" apply false
}
