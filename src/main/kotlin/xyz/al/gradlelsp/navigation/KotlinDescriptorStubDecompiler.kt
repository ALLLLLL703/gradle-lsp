@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeserializedDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.documents.ExternalDocumentStore

/** Builds the same declaration-only Kotlin view used by IntelliJ's Kotlin class-file decompiler. */
internal class KotlinDescriptorStubDecompiler(
    private val documents: ExternalDocumentStore,
) {
    fun decompile(
        descriptor: DeclarationDescriptor,
        parser: KotlinAstParser,
    ): SourceDefinition? {
        val declaration = descriptor.navigationDeclaration()
        val binaryOrigin = binaryOrigin(declaration) ?: return null
        val packageName = declaration.packageName().orEmpty()
        val rendered = renderWithContainers(declaration)
        val text = buildString {
            appendLine("// IntelliJ-style Kotlin decompiler stub generated from a class file")
            appendLine("// Implementation of methods is not available")
            appendLine()
            if (packageName.isNotEmpty()) {
                append("package ").appendLine(packageName)
                appendLine()
            }
            appendLine(rendered)
        }
        val fileName = "${declaration.name.asString()}.decompiled.kt"
        val parsed = parser.parse(fileName, text)
        val target = PsiTreeUtil.collectElementsOfType(parsed.psi, KtNamedDeclaration::class.java)
            .asSequence()
            .lastOrNull { candidate -> candidate.name == declaration.name.asString() }
            ?: return null
        val range = target.nameIdentifier?.textRange ?: return null
        val external = documents.register(
            origin = binaryOrigin,
            displayName = fileName,
            languageId = "kotlin",
            text = text,
        )
        return SourceDefinition(external.uri, external.text, range.startOffset, range.endOffset)
    }

    private fun renderWithContainers(descriptor: DeclarationDescriptor): String {
        var rendered = DescriptorRenderer.FQ_NAMES_IN_TYPES.render(descriptor)
        val containers = generateSequence(descriptor.containingDeclaration) { declaration ->
            declaration.containingDeclaration
        }.filterIsInstance<ClassDescriptor>().toList()
        containers.forEach { container ->
            val keyword = when (container.kind) {
                ClassKind.INTERFACE -> "interface"
                ClassKind.OBJECT -> "object"
                ClassKind.ENUM_CLASS -> "enum class"
                ClassKind.ANNOTATION_CLASS -> "annotation class"
                else -> "class"
            }
            val indented = rendered.lineSequence().joinToString("\n") { line -> "    $line" }
            rendered = "$keyword `${container.name.asString()}` {\n$indented\n}"
        }
        return rendered
    }

    private fun binaryOrigin(descriptor: DeclarationDescriptor): String? {
        val ownSource = (descriptor as? org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource)
            ?.source as? KotlinJvmBinarySourceElement
        ownSource?.binaryClass?.let { binary -> return binary.location }

        val containerSource = (descriptor as? DescriptorWithContainerSource)?.containerSource
        if (containerSource is JvmPackagePartSource) {
            return containerSource.knownJvmBinaryClass?.location
                ?: containerSource.className.internalName
        }

        val containingClass = generateSequence(descriptor.containingDeclaration) { declaration ->
            declaration.containingDeclaration
        }.filterIsInstance<ClassDescriptor>().firstOrNull()
        val classSource = containingClass?.source as? KotlinJvmBinarySourceElement
        classSource?.binaryClass?.let { binary -> return binary.location }

        return if (descriptor is DeserializedDescriptor && descriptor.containingDeclaration is PackageFragmentDescriptor) {
            descriptor.toString()
        } else {
            null
        }
    }
}
