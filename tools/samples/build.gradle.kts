import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core:imaging"))
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("dev.sift.samples.GradeSamplesKt")
    // Same heap the imaging tests get; a 1600px frame needs a fraction of it.
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}
