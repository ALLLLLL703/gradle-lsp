@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.completion

import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.render
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.DslMarkerUtils
import org.jetbrains.kotlin.resolve.calls.inference.CallHandle
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilderImpl
import org.jetbrains.kotlin.resolve.calls.inference.constraintPosition.ConstraintPositionKind
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactoryImpl
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope
import org.jetbrains.kotlin.resolve.scopes.ImportingScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.synthetic.JavaSyntheticPropertiesScope
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.checker.KotlinTypeRefiner
import org.jetbrains.kotlin.types.isError
import xyz.al.gradlelsp.analysis.completionDescriptors
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.analysis.ParsedKotlinFile

/** The dummy is compiler input only; context and replacement boundaries are recovered from PSI. */
internal data class KotlinCompletionContext(
    val file: ParsedKotlinFile,
    val reference: KtSimpleNameExpression,
    val prefix: String,
    val startOffset: Int,
    val endOffset: Int,
    val typePosition: Boolean,
)

internal object KotlinSemanticCompletion {
    private const val IDENTIFIER = "IntellijIdeaRulezzz"
    private const val MAXIMUM_ITEMS = 128

    fun context(parser: KotlinAstParser, document: AnalysisDocument, offset: Int): KotlinCompletionContext? {
        if (offset !in 0..document.text.length) return null
        val original = parser.parse(document.fileName, document.text)
        if (original.psi.importDirectives.any { offset in it.textRange.startOffset..it.textRange.endOffset }) return null
        val file = parser.parse(document.fileName,
            document.text.substring(0, offset) + IDENTIFIER + document.text.substring(offset))
        val leaf = file.psi.findElementAt(offset) ?: return null
        val reference = PsiTreeUtil.getParentOfType(leaf, KtSimpleNameExpression::class.java, false) ?: return null
        if (PsiTreeUtil.getParentOfType(reference, KtImportDirective::class.java, KtPackageDirective::class.java) != null) return null
        val identifier = reference.getReferencedNameElement()
        val range = identifier.textRange
        if (offset < range.startOffset || offset + IDENTIFIER.length > range.endOffset) return null
        val name = reference.getReferencedName()
        val quotingWidth = (identifier.textLength - name.length) / 2
        val prefixLength = offset - range.startOffset - quotingWidth
        if (prefixLength < 0) return null
        // Operator references and labels are not ordinary identifier completion positions.
        if (reference is KtOperationReferenceExpression || reference is KtLabelReferenceExpression) return null
        return KotlinCompletionContext(file, reference, name.take(prefixLength), range.startOffset,
            range.endOffset - IDENTIFIER.length, reference.parent is KtUserType)
    }

    fun complete(parser: KotlinAstParser, position: KotlinCompletionContext,
        binding: BindingContext = parser.bindingContext(position.file)): SourceCompletions {
        val reference = position.reference
        val namedArguments = KotlinNamedArgumentCompletion.complete(position, binding)
        if (reference.parent is org.jetbrains.kotlin.psi.KtValueArgumentName)
            return SourceCompletions(namedArguments.take(MAXIMUM_ITEMS), namedArguments.size > MAXIMUM_ITEMS)
        val scope = generateSequence(reference as KtElement?) { it.parent as? KtElement }
            .firstNotNullOfOrNull { binding[BindingContext.LEXICAL_SCOPE, it] } ?: return SourceCompletions.EMPTY
        val kindFilter = if (position.typePosition) DescriptorKindFilter.CLASSIFIERS
            else DescriptorKindFilter.ALL.withoutKinds(DescriptorKindFilter.PACKAGES_MASK)
        val nameFilter: (Name) -> Boolean = { !it.isSpecial && it.asString().startsWith(position.prefix, ignoreCase = true) }
        val qualified = (reference.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === reference }
            ?: (reference.parent as? KtCallExpression)?.let { call ->
                (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
            }
        val userQualifier = (reference.parent as? KtUserType)?.qualifier?.referenceExpression
        val callableReference = reference.parent as? KtCallableReferenceExpression
        val receiverExpression = qualified?.receiverExpression ?: userQualifier ?: callableReference?.receiverExpression
        val implicit = visibleImplicitReceivers(scope)
        val levels = scope.parentsWithSelf.toList()
        val declarationRanks = mutableMapOf<HierarchicalScope, Int>()
        val receiverRanks = mutableMapOf<ReceiverValue, Int>()
        var nextRank = 1 // explicit members/qualifiers precede lexical candidates
        // Like the compiler scope tower, local declarations precede implicit receiver members,
        // even when a nearer lambda introduces its own receiver. Nonlocal scopes and their
        // receivers then follow the same lexical hierarchy, not independent depth/index scales.
        levels.filterIsInstance<LexicalScope>().filter { it.kind.withLocalDescriptors }.forEach {
            declarationRanks[it] = nextRank++
        }
        levels.filterIsInstance<LexicalScope>().forEach { level ->
            if (!level.kind.withLocalDescriptors) declarationRanks[level] = nextRank++
            (listOfNotNull(level.implicitReceiver) + level.contextReceiversGroup).forEach { receiver ->
                if (receiver.value in implicit) receiverRanks.putIfAbsent(receiver.value, nextRank++)
            }
        }
        val extensionRank = nextRank
        nextRank += levels.size
        levels.filterIsInstance<ImportingScope>().forEach { declarationRanks[it] = nextRank++ }
        val explicit = receiverExpression?.let { expression ->
            (binding.getType(expression) ?: binding[BindingContext.DOUBLE_COLON_LHS, expression]?.type)?.takeUnless { it.isError }?.let { ExpressionReceiver.create(expression, it, binding) }
        }
        val receivers = if (receiverExpression != null) listOfNotNull(explicit) else implicit
        val receiverVariants = receivers.flatMap { receiver ->
            receiverTypes(receiver, binding, scope, reference, qualified is KtSafeQualifiedExpression).map { type ->
                ReceiverCandidate(if (receiver is ExpressionReceiver) receiver.replaceType(type) else receiver, type,
                    if (receiverExpression != null) 0 else receiverRanks.getValue(receiver))
            }
        }
        val candidates = mutableListOf<CompletionCandidate>()
        // Request-scoped compiler synthesis, not a hand-written JavaBean name transformation or cache.
        val syntheticProperties = JavaSyntheticPropertiesScope(LockBasedStorageManager.NO_LOCKS,
            LookupTracker.DO_NOTHING, KotlinTypeRefiner.Default,
            LanguageVersionSettingsImpl.DEFAULT.supportsFeature(LanguageFeature.JvmRecordSupport))
        fun add(descriptor: DeclarationDescriptor, rank: Int, receiver: ReceiverValue? = null) {
            if (!nameFilter(descriptor.name) || !kindFilter.accepts(descriptor) || descriptor is ConstructorDescriptor) return
            if (descriptor is DeclarationDescriptorWithVisibility &&
                DescriptorVisibilities.findInvisibleMember(receiver ?: DescriptorVisibilities.ALWAYS_SUITABLE_RECEIVER,
                    descriptor, scope.ownerDescriptor, false) != null) return
            candidates += CompletionCandidate(descriptor, rank)
        }
        if (receiverExpression != null) {
            val qualifierReference = (if (receiverExpression is KtQualifiedExpression) receiverExpression.selectorExpression
                else receiverExpression) as? KtReferenceExpression
            val target = qualifierReference?.let { binding[BindingContext.REFERENCE_TARGET, it] }
            val staticScopes = when (target) {
                is ClassDescriptor -> listOf(target.staticScope, target.unsubstitutedInnerClassesScope) +
                    listOfNotNull(target.companionObjectDescriptor?.defaultType?.memberScope)
                is TypeAliasDescriptor -> listOfNotNull(target.classDescriptor?.unsubstitutedInnerClassesScope)
                is PackageViewDescriptor -> listOf(target.memberScope)
                else -> emptyList()
            }
            staticScopes.forEach { memberScope ->
                memberScope.completionDescriptors(kindFilter, nameFilter).forEach { add(it, 0) }
            }
        }
        receiverVariants.forEach { (receiver, type, rank) ->
            if (!TypeUtils.isNullableType(type)) {
                type.memberScope.completionDescriptors(kindFilter, nameFilter)
                    .filterNot { it is CallableDescriptor && it.extensionReceiverParameter != null }
                    .forEach { add(it, rank, receiver) }
                if (!position.typePosition) {
                    val record = (type.constructor.declarationDescriptor as? JavaClassDescriptor)?.isRecord == true
                    val propertyNames = type.memberScope.getFunctionNames().asSequence().flatMap { name ->
                        SyntheticJavaPropertyDescriptor.propertyNamesByAccessorName(name).asSequence() +
                            if (record) sequenceOf(name) else emptySequence()
                    }.filter(nameFilter).distinct()
                    propertyNames.flatMap { name ->
                        syntheticProperties.getSyntheticExtensionProperties(listOf(type), name, NoLookupLocation.FROM_IDE)
                    }.mapNotNull { substituteExtension(it, type) }
                        .forEach { add(it, rank, receiver) }
                }
            }
        }
        // ImportingScope's alias-aware enumeration preserves the name actually visible at the caret.
        levels.forEachIndexed { depth, level ->
            val declarations = if (level is ImportingScope) level.getContributedDescriptors(kindFilter, nameFilter, true)
                else level.getContributedDescriptors(kindFilter, nameFilter)
            val memberExtensions = if (level is LexicalScope && level.implicitReceiver?.value in implicit) level.implicitReceiver?.type?.memberScope
                ?.completionDescriptors(kindFilter, nameFilter).orEmpty() else emptyList()
            (declarations + memberExtensions).asSequence()
                .filter { nameFilter(it.name) && kindFilter.accepts(it) }
                .forEach { descriptor ->
                val callable = descriptor as? CallableDescriptor
                if (callable?.extensionReceiverParameter != null) {
                    receiverVariants.forEach { (receiver, type) ->
                        substituteExtension(callable, type)?.let { add(it, extensionRank + depth, receiver) }
                    }
                } else if (receiverExpression == null && descriptor !in memberExtensions) {
                    add(descriptor, declarationRanks.getValue(level))
                }
            }
        }
        val shadowed = mutableSetOf<String>()
        val matches = candidates.sortedWith(compareBy<CompletionCandidate> { it.rank }
            .thenBy { it.descriptor.name.asString() }.thenBy { it.signature })
            .asSequence()
            .filter { (descriptor) ->
                val key = shadowKey(descriptor)
                val extension = (descriptor as? CallableDescriptor)?.extensionReceiverParameter
                if (extension == null) shadowed.add(key)
                else key !in shadowed && shadowed.add("$key@${RENDERER.renderType(extension.type)}")
            }
            .flatMap { candidate -> insertionItems(candidate.descriptor, position, scope).map { it.copy(
                sortText = candidate.rank.toString().padStart(3, '0') + ":" + candidate.descriptor.name.asString() + ":" + candidate.signature) } }
            // Do not resolve constructors/render signatures for items the response cannot contain.
            .take(MAXIMUM_ITEMS + 1).toList()
        val merged = (namedArguments + matches).sortedBy { it.sortText }
        return SourceCompletions(merged.take(MAXIMUM_ITEMS), merged.size > MAXIMUM_ITEMS)
    }

    private fun insertionItems(descriptor: DeclarationDescriptor, position: KotlinCompletionContext,
        scope: LexicalScope): List<SourceCompletionItem> {
        val base = item(descriptor, position.startOffset, position.endOffset)
        val reference = position.reference
        val call = reference.parent as? KtCallExpression
        val invocation = !position.typePosition && reference.parent !is KtCallableReferenceExpression &&
            reference.parent !is KtSimpleNameStringTemplateEntry &&
            (reference.parent as? KtQualifiedExpression)?.receiverExpression !== reference &&
            (call == null || (call.valueArgumentList == null && call.typeArgumentList == null && call.lambdaArguments.isEmpty()))
        if (!invocation) return listOf(base)
        val functions = when (descriptor) {
            is FunctionDescriptor -> listOf(descriptor)
            is ClassDescriptor -> if (descriptor.kind == ClassKind.CLASS && descriptor.modality != org.jetbrains.kotlin.descriptors.Modality.ABSTRACT)
                descriptor.constructors.filter { DescriptorVisibilities.isVisibleIgnoringReceiver(it, scope.ownerDescriptor, false) }
                else emptyList()
            is TypeAliasDescriptor -> descriptor.constructors.filter { DescriptorVisibilities.isVisibleIgnoringReceiver(it, scope.ownerDescriptor, false) }
            else -> emptyList()
        }
        if (functions.isEmpty()) return listOf(base)
        return functions.map { function ->
            val required = function.valueParameters.filter { !it.declaresDefaultValue() && it.varargElementType == null }
            val parameters = if (function.hasStableParameterNames()) required
                else function.valueParameters.take((required.lastOrNull()?.index ?: -1) + 1)
            val arguments = parameters.mapIndexed { index, parameter ->
                val name = parameter.name.render().replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}")
                val named = function.hasStableParameterNames() && parameter.index != index
                (if (named) "$name = " else "") + "\${" + (index + 1) + ":" + name + "}"
            }.joinToString(", ")
            base.copy(insertText = base.insertText + "()", snippetText = base.insertText.replace("$", "\\$") + "(" + arguments + ")\$0",
                detail = signature(function))
        }
    }

    private data class CompletionCandidate(val descriptor: DeclarationDescriptor, val rank: Int) {
        val signature: String by lazy { signature(descriptor) }
    }
    private data class ReceiverCandidate(val receiver: ReceiverValue, val type: KotlinType, val rank: Int)

    private fun visibleImplicitReceivers(scope: LexicalScope): List<ReceiverValue> {
        val markers = mutableSetOf<org.jetbrains.kotlin.name.FqName>()
        return scope.getImplicitReceiversHierarchy().map { it.value }.filter { receiver ->
            val current = DslMarkerUtils.extractDslMarkerFqNames(receiver).all()
            val visible = current.none { it in markers }
            markers.addAll(current)
            visible
        }
    }

    private fun receiverTypes(receiver: ReceiverValue, binding: BindingContext, scope: LexicalScope,
        reference: KtExpression, safe: Boolean): List<KotlinType> {
        val settings = LanguageVersionSettingsImpl.DEFAULT
        val value = DataFlowValueFactoryImpl(settings).createDataFlowValue(receiver, binding, scope.ownerDescriptor)
        val flow = binding[BindingContext.DATA_FLOW_INFO_BEFORE, reference]
            ?: (reference.parent as? KtExpression)?.let { binding[BindingContext.DATA_FLOW_INFO_BEFORE, it] }
        val types = buildList {
            add(receiver.type)
            if (receiver is ExpressionReceiver) binding[BindingContext.SMARTCAST, receiver.expression]?.defaultType?.let(::add)
            if (value.isStable) flow?.getCollectedTypes(value, settings)?.let(::addAll)
        }
        val nonNull = safe || (value.isStable && flow?.getStableNullability(value)?.canBeNull() == false)
        val refined = types.filterNot { it.isError }.map { if (nonNull) TypeUtils.makeNotNullable(it) else it }.distinct()
        // A refined member scope already includes inherited members and resolves their overrides.
        // Do not let the original supertype's signature win presentation deduplication.
        return refined.filter { type -> refined.none { other ->
            KotlinTypeChecker.DEFAULT.isSubtypeOf(other, type) && !KotlinTypeChecker.DEFAULT.isSubtypeOf(type, other)
        } }
    }

    /** Infer only from the receiver. Unconstrained callable parameters stay generic until invocation. */
    private fun substituteExtension(descriptor: CallableDescriptor, receiver: KotlinType): CallableDescriptor? {
        val expected = descriptor.extensionReceiverParameter?.type ?: return null
        if (descriptor.typeParameters.isEmpty()) return descriptor.takeIf { KotlinTypeChecker.DEFAULT.isSubtypeOf(receiver, expected) }
        val builder = ConstraintSystemBuilderImpl()
        val fresh = builder.registerTypeVariables(CallHandle.NONE, descriptor.typeParameters)
        builder.addSubtypeConstraint(receiver, fresh.substitute(expected, Variance.INVARIANT), ConstraintPositionKind.RECEIVER_POSITION.position())
        val system = builder.build()
        if (system.status.hasContradiction()) return null
        val inferred = system.typeVariables.mapNotNull { variable ->
            system.getTypeBounds(variable).value?.let { variable.originalTypeParameter.typeConstructor to TypeProjectionImpl(it) }
        }.toMap()
        val substituted = descriptor.substitute(TypeSubstitutor.create(inferred)) ?: return null
        return substituted.takeIf { KotlinTypeChecker.DEFAULT.isSubtypeOf(receiver, it.extensionReceiverParameter!!.type) }
    }

    private fun shadowKey(descriptor: DeclarationDescriptor): String = when (descriptor) {
        is FunctionDescriptor -> descriptor.name.asString() + "<${descriptor.typeParameters.size}>" + descriptor.valueParameters.joinToString(prefix = "(", postfix = ")") {
            RENDERER.renderType(it.type)
        }
        is ClassifierDescriptor -> "type:${descriptor.name}"
        else -> "value:${descriptor.name}"
    }

    fun item(descriptor: DeclarationDescriptor, start: Int, end: Int): SourceCompletionItem {
        val name = descriptor.name.render()
        return SourceCompletionItem(name, DescriptorUtils.getFqName(descriptor).asString(), name, start, end,
            when (descriptor) {
                is ClassDescriptor -> when (descriptor.kind) {
                    ClassKind.INTERFACE, ClassKind.ANNOTATION_CLASS -> SourceCompletionKind.INTERFACE
                    ClassKind.ENUM_CLASS -> SourceCompletionKind.ENUM
                    ClassKind.ENUM_ENTRY -> SourceCompletionKind.ENUM_MEMBER
                    else -> SourceCompletionKind.CLASS
                }
                is TypeAliasDescriptor -> SourceCompletionKind.TYPE_ALIAS
                is TypeParameterDescriptor -> SourceCompletionKind.TYPE_PARAMETER
                is FunctionDescriptor -> if (descriptor.dispatchReceiverParameter != null) SourceCompletionKind.METHOD else SourceCompletionKind.FUNCTION
                is PropertyDescriptor -> SourceCompletionKind.PROPERTY
                else -> SourceCompletionKind.VARIABLE
            }, detail = signature(descriptor))
    }

    private fun signature(descriptor: DeclarationDescriptor) = RENDERER.render(descriptor)
    private val RENDERER = DescriptorRenderer.FQ_NAMES_IN_TYPES.withOptions { withDefinedIn = false }
}
