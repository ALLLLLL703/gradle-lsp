@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.completion

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.name.render
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.inference.CallHandle
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilderImpl
import org.jetbrains.kotlin.resolve.calls.inference.constraintPosition.ConstraintPositionKind
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.isError

/** Candidates come from compiler call resolution, including ambiguous overload targets. */
internal object KotlinNamedArgumentCompletion {
    fun complete(position: KotlinCompletionContext, binding: BindingContext): List<SourceCompletionItem> {
        val reference = position.reference
        val argument = (reference.parent as? KtValueArgument)
            ?: ((reference.parent as? KtValueArgumentName)?.parent as? KtValueArgument) ?: return emptyList()
        if (argument.getArgumentExpression() !== reference && argument.getArgumentName()?.referenceExpression !== reference) return emptyList()
        val call = argument.parent.parent as? KtCallExpression ?: return emptyList()
        val callee = call.calleeExpression as? KtReferenceExpression ?: return emptyList()
        val targets = buildList {
            call.getResolvedCall(binding)?.resultingDescriptor?.let(::add)
            binding[BindingContext.REFERENCE_TARGET, callee]?.let(::add)
            binding[BindingContext.AMBIGUOUS_REFERENCE_TARGET, callee]?.let(::addAll)
        }.flatMap { if (it is ClassDescriptor) it.constructors else listOf(it) }
            .filterIsInstance<CallableDescriptor>().distinctBy { it.original }
        return targets.flatMap { candidate ->
            if (!candidate.hasStableParameterNames()) return@flatMap emptyList()
            val parameters = candidate.valueParameters
            val supplied = mutableSetOf<Int>()
            val constraints = ConstraintSystemBuilderImpl()
            val fresh = constraints.registerTypeVariables(CallHandle.NONE, candidate.typeParameters)
            var applicable = call.typeArguments.isEmpty() || call.typeArguments.size == candidate.typeParameters.size
            call.typeArguments.forEachIndexed { index, projection ->
                val actual = projection.typeReference?.let { binding[BindingContext.TYPE, it] }
                val parameter = candidate.typeParameters.getOrNull(index)
                if (actual != null && parameter != null && !actual.isError) {
                    val expected = fresh.substitute(parameter.defaultType, Variance.INVARIANT)
                    constraints.addSubtypeConstraint(actual, expected, ConstraintPositionKind.SPECIAL.position())
                    constraints.addSubtypeConstraint(expected, actual, ConstraintPositionKind.SPECIAL.position())
                }
            }
            call.valueArguments.forEachIndexed { index, value ->
                if (value === argument) return@forEachIndexed
                val name = value.getArgumentName()?.asName
                val parameter = when {
                    value is KtLambdaArgument -> parameters.lastOrNull()
                    name != null -> parameters.find { it.name == name }
                    else -> parameters.take(index + 1).firstOrNull { it.varargElementType != null } ?: parameters.getOrNull(index)
                }
                if (parameter == null || (!supplied.add(parameter.index) && parameter.varargElementType == null)) {
                    applicable = false
                } else {
                    val actual = value.getArgumentExpression()?.let(binding::getType)
                    if (actual != null && !actual.isError) {
                        val expected = if (value.getSpreadElement() == null) parameter.varargElementType ?: parameter.type else parameter.type
                        constraints.addSubtypeConstraint(actual, fresh.substitute(expected, Variance.INVARIANT),
                            ConstraintPositionKind.VALUE_PARAMETER_POSITION.position(parameter.index))
                    }
                }
            }
            if (!applicable || constraints.build().status.hasContradiction()) return@flatMap emptyList()
            parameters.filter { it.index !in supplied && it.name.asString().startsWith(position.prefix, ignoreCase = true) }
                .map { parameter ->
                    val name = parameter.name.render()
                    SourceCompletionItem(name, name, name + if (argument.getEqualsToken() == null) " = " else "",
                        position.startOffset, position.endOffset, SourceCompletionKind.PARAMETER,
                        detail = KotlinSemanticCompletion.item(candidate, 0, 0).detail,
                        sortText = "000:argument:$name:${candidate}")
                }
        }.distinctBy { it.name to it.detail }
    }
}
