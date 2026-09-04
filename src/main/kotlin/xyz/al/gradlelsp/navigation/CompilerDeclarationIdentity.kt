@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource

internal data class CompilerDeclarationIdentity(
    val kind: Kind,
    val fqName: String,
    val extensionReceiverType: String?,
    val parameterTypes: List<String>,
) {
    enum class Kind {
        CLASS,
        CONSTRUCTOR,
        FUNCTION,
        PROPERTY,
        TYPE_ALIAS,
    }

    companion object {
        fun from(descriptor: DeclarationDescriptor): CompilerDeclarationIdentity? {
            val declaration = descriptor.navigationDeclaration()
            val kind = when (declaration) {
                is ConstructorDescriptor -> Kind.CONSTRUCTOR
                is ClassDescriptor -> Kind.CLASS
                is FunctionDescriptor -> Kind.FUNCTION
                is PropertyDescriptor -> Kind.PROPERTY
                is TypeAliasDescriptor -> Kind.TYPE_ALIAS
                else -> return null
            }
            val callable = declaration as? CallableDescriptor
            return CompilerDeclarationIdentity(
                kind = kind,
                fqName = DescriptorUtils.getFqNameSafe(
                    (declaration as? ConstructorDescriptor)?.constructedClass ?: declaration,
                ).asString(),
                extensionReceiverType = callable?.extensionReceiverParameter?.type?.let(TYPE_RENDERER::renderType),
                parameterTypes = callable?.valueParameters.orEmpty().map { parameter ->
                    TYPE_RENDERER.renderType(parameter.type)
                },
            )
        }

        private val TYPE_RENDERER = DescriptorRenderer.FQ_NAMES_IN_TYPES
    }
}

internal fun DeclarationDescriptor.navigationDeclaration(): DeclarationDescriptor =
    when (this) {
        is PropertyAccessorDescriptor -> correspondingProperty.original
        else -> original
    }

internal fun DeclarationDescriptor.kotlinBinaryOrigin(): String? {
    val declaration = navigationDeclaration()
    val ownSource = (declaration as? DeclarationDescriptorWithSource)
        ?.source as? KotlinJvmBinarySourceElement
    ownSource?.binaryClass?.let { binary -> return binary.stableOrigin() }

    val containerSource = (declaration as? DescriptorWithContainerSource)?.containerSource
    if (containerSource is JvmPackagePartSource) {
        containerSource.knownJvmBinaryClass?.let { binary -> return binary.stableOrigin() }
    }

    val containingClass = generateSequence(declaration.containingDeclaration) { descriptor ->
        descriptor.containingDeclaration
    }.filterIsInstance<ClassDescriptor>().firstOrNull()
    val classSource = containingClass?.source as? KotlinJvmBinarySourceElement
    return classSource?.binaryClass?.stableOrigin()
}

private fun KotlinJvmBinaryClass.stableOrigin(): String = containingLibrary ?: location

internal fun DeclarationDescriptor.packageName(): String? =
    generateSequence(this) { descriptor -> descriptor.containingDeclaration }
        .filterIsInstance<PackageFragmentDescriptor>()
        .firstOrNull()
        ?.fqName
        ?.asString()
