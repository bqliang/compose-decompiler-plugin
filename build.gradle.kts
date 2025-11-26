import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    alias(libs.plugins.gradle.pluginPublish)
}

group = "io.github.bqliang"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.kotlin.gradlePlugin)

    implementation(libs.asm)
    implementation(libs.vineflower)
}

gradlePlugin {
    website = "https://github.com/bqliang/compose-decompiler-plugin"
    vcsUrl = "https://github.com/bqliang/compose-decompiler-plugin.git"

    plugins {
        register("ComposeDecompiler") {
            id = "io.github.bqliang.compose-decompiler"
            implementationClass = "com.bqliang.compose.decompiler.ComposeDecompilerPlugin"

            displayName = "Compose Decompiler"
            description = "A Gradle Plugin to decompile bytecode compiled with Jetpack Compose Compiler Plugin into java/kt file."
            tags = listOf("compose", "bytecode", "decompiler", "android")
        }
    }
}
