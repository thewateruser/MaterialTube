buildscript {
    dependencies {
        // AGP 9 has built-in Kotlin; pin the Kotlin Gradle plugin it runs with.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
