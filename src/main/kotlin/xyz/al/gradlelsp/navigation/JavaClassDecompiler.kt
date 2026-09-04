@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

import org.jd.core.v1.ClassFileToJavaSourceDecompiler
import org.jd.core.v1.api.loader.Loader
import org.jd.core.v1.api.loader.LoaderException
import org.jd.core.v1.api.printer.Printer
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SyntheticPropertyDescriptor
import org.jetbrains.kotlin.load.kotlin.computeJvmDescriptor
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.gradle.GradleKotlinDslModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Decompiles Java declarations directly from the Gradle script classpath using JD-Core. */
internal class JavaClassDecompiler(
    private val documents: ExternalDocumentStore,
) {
    fun decompile(
        descriptor: DeclarationDescriptor,
        model: GradleKotlinDslModel,
        parser: KotlinAstParser,
    ): SourceDefinition? {
        val declaration = descriptor.navigationDeclaration()
        val target = JavaBinaryDeclarationTarget.from(declaration) ?: return null
        val loader = GradleClassPathLoader(model.classPath)
        val resource = loader.find(target.ownerInternalName) ?: return null
        val printer = CapturingPrinter()
        runCatching {
            ClassFileToJavaSourceDecompiler().decompile(loader, printer, target.ownerInternalName)
        }.getOrElse { return null }

        val emitted = printer.declarations.firstOrNull { candidate ->
            target.matches(
                candidate.type,
                candidate.internalTypeName,
                candidate.name,
                candidate.descriptor,
            )
        } ?: return null
        val text = printer.toString()
        val fileName = "${target.ownerInternalName.substringAfterLast('/').substringBefore('$')}.java"
        val parsed = parser.parseJava(fileName, text) ?: return null
        val element = parsed.findElementAt(emitted.startOffset) ?: return null
        val declarationOwner = PsiTreeUtil.getParentOfType(
            element,
            PsiNameIdentifierOwner::class.java,
            false,
        ) ?: return null
        val nameRange = declarationOwner.nameIdentifier?.textRange ?: return null
        if (nameRange.startOffset != emitted.startOffset || nameRange.endOffset != emitted.endOffset) {
            return null
        }

        val external = documents.register(
            origin = resource.origin,
            displayName = fileName,
            languageId = "java",
            text = text,
        )
        return SourceDefinition(external.uri, external.text, nameRange.startOffset, nameRange.endOffset)
    }

    private data class EmittedDeclaration(
        val type: Int,
        val internalTypeName: String?,
        val name: String?,
        val descriptor: String?,
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class ClassResource(
        val bytes: ByteArray,
        val origin: String,
    )

    private class GradleClassPathLoader(
        private val roots: List<Path>,
    ) : Loader {
        override fun canLoad(internalName: String): Boolean = find(internalName) != null

        override fun load(internalName: String): ByteArray =
            find(internalName)?.bytes ?: throw LoaderException("Class not found: $internalName")

        fun find(internalName: String): ClassResource? {
            val entryName = "$internalName.class"
            roots.forEach { root ->
                if (Files.isDirectory(root)) {
                    val classFile = root.resolve(entryName)
                    if (Files.isRegularFile(classFile)) {
                        return ClassResource(Files.readAllBytes(classFile), classFile.toUri().toASCIIString())
                    }
                } else if (Files.isRegularFile(root)) {
                    runCatching {
                        JarFile(root.toFile()).use { jar ->
                            val entry = jar.getJarEntry(entryName) ?: return@use null
                            ClassResource(
                                jar.getInputStream(entry).use { it.readBytes() },
                                "${root.toUri().toASCIIString()}!/$entryName",
                            )
                        }
                    }.getOrNull()?.let { resource -> return resource }
                }
            }
            return null
        }
    }

    private class CapturingPrinter : Printer {
        private val output = StringBuilder()
        private var indentation = 0
        val declarations = mutableListOf<EmittedDeclaration>()

        override fun start(maxLineNumber: Int, majorVersion: Int, minorVersion: Int) = Unit
        override fun end() = Unit
        override fun printText(text: String) = append(text)
        override fun printNumericConstant(constant: String) = append(constant)
        override fun printStringConstant(constant: String, ownerInternalName: String?) = append(constant)
        override fun printKeyword(keyword: String) = append(keyword)

        override fun printDeclaration(
            type: Int,
            internalTypeName: String?,
            name: String?,
            descriptor: String?,
        ) {
            val renderedName = name.orEmpty()
            val start = output.length
            output.append(renderedName)
            declarations += EmittedDeclaration(
                type,
                internalTypeName,
                name,
                descriptor,
                start,
                output.length,
            )
        }

        override fun printReference(
            type: Int,
            internalTypeName: String?,
            name: String?,
            descriptor: String?,
            ownerInternalName: String?,
        ) = append(name.orEmpty())

        override fun indent() {
            indentation++
        }

        override fun unindent() {
            indentation--
        }

        override fun startLine(lineNumber: Int) {
            repeat(indentation) { output.append("  ") }
        }

        override fun endLine() {
            output.append('\n')
        }

        override fun extraLine(count: Int) {
            repeat(count) { output.append('\n') }
        }

        override fun startMarker(type: Int) = Unit
        override fun endMarker(type: Int) = Unit
        override fun toString(): String = output.toString()

        private fun append(text: String) {
            output.append(text)
        }
    }
}

internal data class JavaBinaryDeclarationTarget(
    val type: Int,
    val ownerInternalName: String,
    val name: String,
    val descriptor: String?,
) {
    fun matches(
        candidateType: Int,
        candidateInternalTypeName: String?,
        candidateName: String?,
        candidateDescriptor: String?,
    ): Boolean =
        candidateType == type &&
            candidateInternalTypeName == ownerInternalName &&
            candidateName == name &&
            (descriptor == null || candidateDescriptor == descriptor)

    companion object {
        fun from(descriptor: DeclarationDescriptor): JavaBinaryDeclarationTarget? =
            when (descriptor) {
                is SyntheticPropertyDescriptor -> from(descriptor.getMethod)

                is ConstructorDescriptor -> {
                    val owner = descriptor.constructedClass
                    JavaBinaryDeclarationTarget(
                        Printer.CONSTRUCTOR,
                        owner.jvmInternalName() ?: return null,
                        owner.name.asString(),
                        descriptor.computeJvmDescriptor(withName = false),
                    )
                }

                is ClassDescriptor -> {
                    val internalName = descriptor.jvmInternalName() ?: return null
                    JavaBinaryDeclarationTarget(Printer.TYPE, internalName, descriptor.name.asString(), null)
                }

                is FunctionDescriptor -> {
                    val owner = descriptor.containingDeclaration as? ClassDescriptor ?: return null
                    JavaBinaryDeclarationTarget(
                        Printer.METHOD,
                        owner.jvmInternalName() ?: return null,
                        descriptor.name.asString(),
                        descriptor.computeJvmDescriptor(withName = false),
                    )
                }

                is PropertyDescriptor -> {
                    val owner = descriptor.containingDeclaration as? ClassDescriptor ?: return null
                    JavaBinaryDeclarationTarget(
                        Printer.FIELD,
                        owner.jvmInternalName() ?: return null,
                        descriptor.name.asString(),
                        null,
                    )
                }

                else -> null
            }
    }
}

internal fun ClassDescriptor.jvmInternalName(): String? {
    val classes = generateSequence(this) { descriptor ->
        descriptor.containingDeclaration as? ClassDescriptor
    }.toList().asReversed()
    if (classes.any { it.name.isSpecial }) return null
    val packageName = packageName().orEmpty().replace('.', '/')
    val className = classes.joinToString("$") { it.name.asString() }
    return if (packageName.isEmpty()) className else "$packageName/$className"
}
