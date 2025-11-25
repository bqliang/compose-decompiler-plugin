package com.bqliang.compose.decompiler

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.objectweb.asm.*
import java.io.File
import java.io.FileInputStream

/**
 * Core Task:
 * 1. Scan .class files in the input directory.
 * 2. Filter classes containing methods annotated with @Composable.
 * 3. Decompile these classes to the output directory.
 */
abstract class DecompileComposableTask : DefaultTask() {

    // input: .class files directory
    @get:InputDirectory
    abstract val inputClassDir: DirectoryProperty

    // output: decompiled .java files directory
    @get:OutputDirectory
    abstract val outputSourceDir: DirectoryProperty

    @get:Input
    abstract val ktEnable: Property<Boolean>

    @get:InputFiles
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @TaskAction
    fun execute() {
        val inputDir = inputClassDir.get().asFile
        val outputDir = outputSourceDir.get().asFile

        logger.info("Output directory: ${outputDir.absolutePath}")
        logger.info("Input directory: ${inputDir.absolutePath}")

        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        val classesToDecompile = mutableListOf<String>()

        inputDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                if (hasComposableMethod(classFile)) {
                    classesToDecompile.add(classFile.absolutePath)
                }
            }

        if (classesToDecompile.isEmpty()) {
            logger.info("Skipping decomplication: No @Composable functions found in ${inputDir.name}")
            return
        } else {
            logger.info("Found ${classesToDecompile.size} classes with @Composable annotations.")
            classesToDecompile.forEach {
                logger.info("Processing: $it")
            }
        }

        try {
            decompileClassFilesByVineflower(
                classFilePaths = classesToDecompile,
                outputDir = outputDir,
                libraries = classpath.files
            )
            logger.lifecycle("Compose Decompiler output directory: ${outputDir.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to decompile classes", e)
        }
    }

    private fun hasComposableMethod(file: File): Boolean {
        var found = false
        FileInputStream(file).use { stream ->
            val classReader = ClassReader(stream)

            /**
             * Performance Optimization: We only check for the existence of the @Composable annotation.
             * 1. SKIP_CODE: Skips parsing the actual method bytecode instructions (body), which is the
             *    most expensive part.
             * 2. SKIP_DEBUG: Skips parsing debug info like line numbers, local variable names, and source
             *    files. By combining these flags, we significantly reduce I/O overhead and parsing time
             *    for scanning large numbers of class files.
             */
            val parsingOptions = ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG
            classReader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitAnnotation(
                                descriptor: String?,
                                visible: Boolean,
                            ): AnnotationVisitor? {
                                if (descriptor == "Landroidx/compose/runtime/Composable;") {
                                    found = true
                                }
                                return super.visitAnnotation(descriptor, visible)
                            }
                        }
                    }
                },
                parsingOptions,
            )
        }
        return found
    }

    private fun decompileClassFilesByVineflower(
        classFilePaths: List<String>,
        outputDir: File,
        libraries: Set<File>,
    ) {
        val classFiles = classFilePaths.map { File(it) }.toTypedArray()
        val decompiler = Decompiler.Builder()
            .inputs(*classFiles)
            .libraries(*libraries.toTypedArray())
            .output(object : DirectoryResultSaver(outputDir) {

                override fun saveClassFile(
                    path: String?,
                    qualifiedName: String?,
                    entryName: String?,
                    content: String?,
                    mapping: IntArray?,
                ) {
                    if (content.isNullOrBlank() || entryName.isNullOrBlank()) {
                        return
                    }
                    val classFile = outputDir
                        .resolve(path.orEmpty().ifBlank { "" })
                        .let { dir ->
                            if (!qualifiedName.isNullOrBlank()) {
                                dir.resolve(qualifiedName).parentFile
                            } else {
                                dir
                            }
                        }
                        .resolve(entryName)

                    try {
                        classFile.parentFile.mkdirs() // make sure parent directory exists
                        classFile.writeText(content)
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to save class", e)
                    }
                }
            })
            .option(IFernflowerPreferences.BOOLEAN_TRUE_ONE, true) // 将 0/1 还原为 boolean (true/false)
            .option(IFernflowerPreferences.REMOVE_SYNTHETIC, false) // 保留合成代码
            .option(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, true) // 还原泛型
            .option(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, true) // 尽可能利用 classpath 推断类型
            .option(IFernflowerPreferences.DECOMPILE_INNER, true) // 反编译内部类
            .option(IFernflowerPreferences.REMOVE_BRIDGE, true) // 移除桥接方法
            .option(
                "kt-enable",
                ktEnable.getOrElse(false)
            ) // 将 Kotlin 反编译为 kt 而不是 java, see more at https://github.com/Vineflower/vineflower/blob/35f2c6e2b65746d8fc4358ced8de03d440f2b80b/plugins/kotlin/src/main/java/org/vineflower/kotlin/KotlinOptions.java
            .build()

        decompiler.decompile()
    }
}
