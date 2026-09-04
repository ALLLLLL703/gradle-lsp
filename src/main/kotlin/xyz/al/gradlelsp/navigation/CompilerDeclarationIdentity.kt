@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.DescriptorUtils

internal data class CompilerDeclarationIdentity(
    val kind: Kind,
    val fqName: String,
    val extensionReceiverType: String?,
    val parameterTypes: List<String>,
) {
    enum class Kind {
        CLASS,
        FUNCTION,
        PROPERTY,
        TYPE_ALIAS,
    }

    companion object {
        fun from(descriptor: DeclarationDescriptor): CompilerDeclarationIdentity? {
            val declaration = descriptor.navigationDeclaration()
            val kind = when (declaration) {
                is ClassDescriptor -> Kind.CLASS
                is FunctionDescriptor -> Kind.FUNCTION
                is PropertyDescriptor -> Kind.PROPERTY
                is TypeAliasDescriptor -> Kind.TYPE_ALIAS
                else -> return null
            }
            val callable = declaration as? CallableDescriptor
            return CompilerDeclarationIdentity(
                kind = kind,
                fqName = DescriptorUtils.getFqNameSafe(declaration).asString(),
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
        is PropertyAccessorDescriptor -> correspondingProperty
        is ConstructorDescriptor -> constructedClass
        else -> original
    }

internal fun DeclarationDescriptor.packageName(): String? =
    generateSequence(this) { descriptor -> descriptor.containingDeclaration }
        .filterIsInstance<PackageFragmentDescriptor>()
        .firstOrNull()
        ?.fqName
        ?.asString()
