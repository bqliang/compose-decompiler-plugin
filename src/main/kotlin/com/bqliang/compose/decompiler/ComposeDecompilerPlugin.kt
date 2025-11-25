package com.bqliang.compose.decompiler

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class ComposeDecompilerPlugin : Plugin<Project> {

    interface DecomposerExtension {
        val outputDir: DirectoryProperty
        val ktEnable: Property<Boolean>
    }

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
            val extension = project.extensions.create("composeDecompiler", DecomposerExtension::class.java)
            extension.outputDir.convention(project.layout.buildDirectory.dir("compose_decompiled"))
            extension.ktEnable.convention(false)

            project.tasks.withType<KotlinCompile> {
                val kotlinCompileTask: KotlinCompile = this@withType

                if (
                    !kotlinCompileTask.name.startsWith("compile") ||
                    !kotlinCompileTask.name.contains("Kotlin", ignoreCase = true) ||
                    kotlinCompileTask.name.contains(other = "test", ignoreCase = true) ||
                    kotlinCompileTask.name.contains(other = "ksp", ignoreCase = true) ||
                    kotlinCompileTask.name.contains(other = "kapt", ignoreCase = true)
                ) return@withType

                val taskName = ("de" + kotlinCompileTask.name).replace("Kotlin", "Composable")

                project.tasks.register<DecompileComposableTask>(taskName) {
                    group = "compose decompiler"
                    description = "decompile Composables bytecode to java/kt"

                    val dirName = kotlinCompileTask.name
                        .replace(oldValue = "compile", newValue = "")
                        .replace(oldValue = "Kotlin", newValue = "", ignoreCase = true)
                    outputSourceDir.set(extension.outputDir.dir(dirName))
                    ktEnable.set(extension.ktEnable)

                    // class files are generated in compile<...>Kotlin task
                    // so we can find it by name,
                    // establish task dependency and set its destinationDirectory as input
                    dependsOn(kotlinCompileTask.name)
                    inputClassDir.set(kotlinCompileTask.destinationDirectory)

                    classpath.from(kotlinCompileTask.libraries)
                }
            }
        }
    }
}
