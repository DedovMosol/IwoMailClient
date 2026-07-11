plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
    // Kover 0.7.6: последняя 0.7.x, стабильна с AGP 8 / Gradle 8.12 (0.8/0.9 имеют
    // регрессии variant-resolution на Android+Gradle 8.11+, см. kotlinx-kover#728)
    id("org.jetbrains.kotlinx.kover") version "0.7.6" apply false
}
