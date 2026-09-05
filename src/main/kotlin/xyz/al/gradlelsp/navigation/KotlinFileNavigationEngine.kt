@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaDocumentedElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorEquivalenceForOverrides
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isError
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.analysis.KotlinGradleScriptTemplate
import xyz.al.gradlelsp.analysis.KotlinScriptAnalysisContext
import xyz.al.gradlelsp.analysis.ParsedKotlinFile
import xyz.al.gradlelsp.completion.KotlinImportCompletion
import xyz.al.gradlelsp.completion.SourceCompletions
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.documents.GradleWorkspaceDocumentSource
import xyz.al.gradlelsp.documents.WorkspaceDocumentSource
import xyz.al.gradlelsp.gradle.GradleKotlinDslModel
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelLoader
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelProvider
import java.net.URI
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class KotlinFileNavigationEngine(
    private val modelProvider: GradleKotlinDslModelProvider = GradleKotlinDslModelLoader(),
    private val externalDocuments: ExternalDocumentStore = ExternalDocumentStore(),
    private val workspaceDocuments: WorkspaceDocumentSource = GradleWorkspaceDocumentSource(DocumentStore()),
    private val localParser: KotlinAstParser = KotlinAstParser(),
) : DocumentNavigationEngine {
    private val closed = AtomicBoolean(false)
    private val modelParsers = LinkedHashMap<ParserKey, KotlinAstParser>(8, 0.75f, true)
    private val pinnedModelParsers = mutableMapOf<ParserKey, PinnedParser>()
    private val externalSources = KotlinExternalSourceResolver(externalDocuments)

    override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> {
        check(!closed.get()) { "Kotlin navigation engine is closed" }

        val localFile = localParser.parse(document.fileName, document.text)
        semanticDeclarationAt(localFile, offset)?.let { declaration ->
            return listOf(sourceDefinition(document, declaration))
        }
        resolveLocalReference(localFile, offset)?.let { declaration ->
            return listOf(sourceDefinition(document, declaration))
        }

        val script = Path.of(URI.create(document.uri))
        val model = modelProvider.modelFor(script)
        val parser = parserFor(document.fileName, model)
        val parsedFile = parser.parse(document.fileName, document.text)
        val descriptors = resolveDescriptorsAt(parser, parsedFile, offset)
        return descriptors.flatMap { descriptor ->
            val sourceDeclaration = DescriptorToSourceUtils.descriptorToDeclaration(descriptor)
            if (sourceDeclaration?.containingFile === parsedFile.psi) {
                listOf(sourceDefinition(document, sourceDeclaration))
            } else {
                externalSources.resolve(descriptor, model, parser)
            }
        }.distinctBy { definition ->
            listOf(definition.uri, definition.startOffset, definition.endOffset)
        }
    }

    override fun typeDefinitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> {
        check(!closed.get()) { "Kotlin navigation engine is closed" }

        val localFile = localParser.parse(document.fileName, document.text)
        localTypeDefinitions(document, localFile, offset).takeIf(List<*>::isNotEmpty)?.let { return it }

        val script = Path.of(URI.create(document.uri))
        val model = modelProvider.modelFor(script)
        val parser = parserFor(document.fileName, model)
        val parsedFile = parser.parse(document.fileName, document.text)
        return typeDescriptorsAt(parser, parsedFile, offset).flatMap { descriptor ->
            val sourceDeclaration = DescriptorToSourceUtils.descriptorToDeclaration(descriptor)
                as? KtNamedDeclaration
            if (sourceDeclaration?.containingFile === parsedFile.psi) {
                listOf(sourceDefinition(document, sourceDeclaration))
            } else {
                externalSources.resolve(descriptor, model, parser)
            }
        }.distinctBy { definition ->
            listOf(definition.uri, definition.startOffset, definition.endOffset)
        }
    }

    override fun references(
        document: AnalysisDocument,
        offset: Int,
        includeDeclaration: Boolean,
    ): List<SourceDefinition> {
        check(!closed.get()) { "Kotlin navigation engine is closed" }
        return withTargetAnalysis(document, offset) { analysis ->
            val references = mutableListOf<SourceDefinition>()
            workspaceDocuments.forEachDocument(document) { candidateDocument ->
                try {
                    analyzeWorkspaceDocument(candidateDocument, analysis.usesGradleModel) { parsedFile, context ->
                        referenceOccurrences(parsedFile, context).forEach { reference ->
                            val matches = reference.descriptors
                                .flatMap { descriptor ->
                                    buildList {
                                        add(semanticTarget(descriptor, parsedFile, candidateDocument))
                                        if (descriptor is ConstructorDescriptor) {
                                            add(
                                                semanticTarget(
                                                    descriptor.constructedClass,
                                                    parsedFile,
                                                    candidateDocument,
                                                ),
                                            )
                                        }
                                    }
                                }
                                .any { candidate ->
                                    analysis.targets.any { target -> sameTarget(target, candidate) }
                                }
                            if (matches) {
                                references += SourceDefinition(
                                    candidateDocument.uri,
                                    candidateDocument.text,
                                    reference.range.startOffset,
                                    reference.range.endOffset,
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // One invalid or unavailable script model must not abort the workspace scan.
                }
            }
            if (includeDeclaration) {
                references += declarationDefinitions(analysis)
            }
            references.distinctBy { reference ->
                listOf(reference.uri, reference.startOffset, reference.endOffset)
            }.sortedWith(
                compareBy<SourceDefinition> { reference -> reference.uri }
                    .thenBy { reference -> reference.startOffset }
                    .thenBy { reference -> reference.endOffset },
            )
        }
    }

    private fun declarationDefinitions(analysis: TargetAnalysis): List<SourceDefinition> {
        val local = analysis.targets.mapNotNull { target -> target.source?.definition }
        val externalTargets = analysis.targets.filter { target -> target.source == null }
        if (externalTargets.isEmpty()) return local

        val model = analysis.model ?: try {
            val script = Path.of(URI.create(analysis.document.uri))
            modelProvider.modelFor(script)
        } catch (_: Exception) {
            return local
        }
        val parser = if (analysis.model != null) {
            analysis.parser
        } else {
            parserFor(analysis.document.fileName, model)
        }
        val external = externalTargets.flatMap { target ->
            try {
                externalSources.resolve(target.descriptor, model, parser)
            } catch (_: Exception) {
                emptyList()
            }
        }
        return local + external
    }

    override fun implementations(document: AnalysisDocument, offset: Int): List<SourceDefinition> {
        check(!closed.get()) { "Kotlin navigation engine is closed" }
        return withTargetAnalysis(document, offset) { analysis ->
            val targets = implementationTargets(analysis)
            if (targets.isEmpty()) return@withTargetAnalysis emptyList()

            val implementations = mutableListOf<SourceDefinition>()
            workspaceDocuments.forEachDocument(document) { candidateDocument ->
                try {
                    analyzeWorkspaceDocument(candidateDocument, analysis.usesGradleModel) { parsedFile, context ->
                        PsiTreeUtil.collectElementsOfType(parsedFile.psi, KtDeclaration::class.java)
                            .asSequence()
                            .filterNot { declaration -> declaration is KtPropertyAccessor }
                            .mapNotNull { declaration ->
                                declarationDescriptor(context, declaration)?.let { descriptor ->
                                    descriptor to declaration
                                }
                            }
                            .filter { (descriptor) ->
                                when (descriptor) {
                                    is ClassDescriptor ->
                                        descriptor.kind != ClassKind.ENUM_ENTRY &&
                                            targets.filterIsInstance<ImplementationTarget.Class>()
                                                .any { target ->
                                                    hasTargetSupertype(
                                                        descriptor,
                                                        target.target,
                                                        parsedFile,
                                                        candidateDocument,
                                                    )
                                                }
                                    is CallableMemberDescriptor ->
                                        descriptor !is ConstructorDescriptor &&
                                            descriptor.kind == CallableMemberDescriptor.Kind.DECLARATION &&
                                            targets.filterIsInstance<ImplementationTarget.Callable>()
                                                .any { target ->
                                                    overridesTarget(
                                                        descriptor,
                                                        target.target,
                                                        parsedFile,
                                                        candidateDocument,
                                                    )
                                                }
                                    else -> false
                                }
                            }
                            .mapNotNullTo(implementations) { (descriptor) ->
                                semanticTarget(descriptor, parsedFile, candidateDocument).source?.definition
                            }
                    }
                } catch (_: Exception) {
                    // Continue with other scripts when one model or recovered file cannot be analyzed.
                }
            }
            implementations.distinctBy { implementation ->
                listOf(implementation.uri, implementation.startOffset, implementation.endOffset)
            }.sortedWith(
                compareBy<SourceDefinition> { implementation -> implementation.uri }
                    .thenBy { implementation -> implementation.startOffset }
                    .thenBy { implementation -> implementation.endOffset },
            )
        }
    }

    override fun completeImports(document: AnalysisDocument, offset: Int): SourceCompletions {
        check(!closed.get()) { "Kotlin navigation engine is closed" }
        val context = KotlinImportCompletion.context(localParser, document, offset) ?: return SourceCompletions.EMPTY
        val model = modelProvider.modelFor(Path.of(URI.create(document.uri)))
        // Share the bounded compiler environment with navigation; package lookup needs no BindingContext.
        return withPinnedParser(document.fileName, model) { parser ->
            KotlinImportCompletion.complete(context, parser.subPackages(context.qualifier))
        }
    }

    override fun hover(document: AnalysisDocument, offset: Int): SourceHover? {
        check(!closed.get()) { "Kotlin navigation engine is closed" }

        val localAnalysis = runCatching {
            targetAnalysis(localParser, document, offset, usesGradleModel = false, model = null)
        }.getOrNull()
        if (localAnalysis != null && localAnalysis.targets.isNotEmpty()) {
            return sourceHover(localAnalysis, offset)
        }

        val script = Path.of(URI.create(document.uri))
        val model = modelProvider.modelFor(script)
        return withPinnedParser(document.fileName, model) { parser ->
            val analysis = targetAnalysis(parser, document, offset, usesGradleModel = true, model = model)
            sourceHover(analysis, offset)
        }
    }

    private fun sourceHover(analysis: TargetAnalysis, offset: Int): SourceHover? {
        val target = analysis.targets.firstOrNull() ?: return null
        val range = hoverRangeAt(analysis.file, offset) ?: return null
        val signature = runCatching {
            renderHoverSignature(target.descriptor)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        val localSource = target.source?.definition
        val externalSource = if (localSource == null) {
            runCatching { resolveExternalHoverSource(analysis, target.descriptor) }.getOrNull()
        } else {
            null
        }
        val source = localSource ?: externalSource?.definition
        val documentation = runCatching {
            if (localSource != null) {
                kotlinDocumentation(analysis.file, localSource)
            } else {
                externalSource?.let { resolved ->
                    externalDocumentation(resolved.definition, resolved.parser)
                }
            }
        }.getOrNull()
        return SourceHover(
            signature = signature,
            documentation = documentation,
            source = source,
            startOffset = range.startOffset,
            endOffset = range.endOffset,
        )
    }

    private fun renderHoverSignature(descriptor: DeclarationDescriptor): String {
        val declaration = descriptor.navigationDeclaration()
        return if (declaration is ConstructorDescriptor) {
            HOVER_CONSTRUCTOR_RENDERER.render(declaration)
        } else {
            HOVER_RENDERER.render(declaration)
        }
    }

    private fun resolveExternalHoverSource(
        analysis: TargetAnalysis,
        descriptor: DeclarationDescriptor,
    ): ResolvedHoverSource? {
        val model = analysis.model ?: run {
            val script = Path.of(URI.create(analysis.document.uri))
            modelProvider.modelFor(script)
        }
        val parser = if (analysis.model != null) {
            analysis.parser
        } else {
            parserFor(analysis.document.fileName, model)
        }
        val definition = externalSources.resolve(descriptor, model, parser).firstOrNull() ?: return null
        return ResolvedHoverSource(definition, parser)
    }

    private fun kotlinDocumentation(
        file: ParsedKotlinFile,
        source: SourceDefinition,
    ): String? = kotlinDeclarationAt(file, source)?.let(SourceDocumentation::kotlin)

    private fun externalDocumentation(
        source: SourceDefinition,
        parser: KotlinAstParser,
    ): String? {
        val external = externalDocuments.find(source.uri) ?: return null
        val fileName = URI.create(source.uri).path.substringAfterLast('/').ifBlank { "external-source" }
        return when (external.languageId) {
            "kotlin" -> {
                val parsed = parser.parse(fileName, external.text)
                kotlinDeclarationAt(parsed, source)?.let(SourceDocumentation::kotlin)
            }
            "java" -> {
                val parsed = parser.parseJava(fileName, external.text) ?: return null
                val declaration = sequenceOf(source.startOffset, source.startOffset - 1)
                    .distinct()
                    .filter { offset -> offset in 0 until parsed.textLength }
                    .mapNotNull(parsed::findElementAt)
                    .flatMap { element -> generateSequence(element) { current -> current.parent } }
                    .filterIsInstance<PsiJavaDocumentedElement>()
                    .firstOrNull()
                declaration?.let(SourceDocumentation::java)
            }
            else -> null
        }
    }

    private fun kotlinDeclarationAt(
        file: ParsedKotlinFile,
        source: SourceDefinition,
    ): KtDeclaration? = elementsAround(file, source.startOffset)
        .flatMap { element -> generateSequence(element) { current -> current.parent } }
        .filterIsInstance<KtDeclaration>()
        .filter { declaration ->
            declarationIdentifierRange(declaration)?.let { range ->
                range.startOffset == source.startOffset && range.endOffset == source.endOffset
            } == true
        }
        .minByOrNull { declaration -> declaration.textRange.length }

    private fun implementationTargets(analysis: TargetAnalysis): List<ImplementationTarget> =
        analysis.targets.mapNotNull { target ->
            when (val descriptor = target.descriptor) {
                is ConstructorDescriptor -> semanticTarget(
                    descriptor.constructedClass,
                    analysis.file,
                    analysis.document,
                ).let(ImplementationTarget::Class)
                is TypeAliasDescriptor -> (classifierOf(descriptor.expandedType) as? ClassDescriptor)
                    ?.let { expanded -> semanticTarget(expanded, analysis.file, analysis.document) }
                    ?.let(ImplementationTarget::Class)
                is ClassDescriptor -> ImplementationTarget.Class(target)
                is CallableMemberDescriptor -> ImplementationTarget.Callable(target)
                else -> null
            }
        }.distinctBy { target -> target.target.source ?: target.target.descriptor.original }

    private fun hasTargetSupertype(
        candidate: ClassDescriptor,
        target: SemanticTarget,
        file: ParsedKotlinFile,
        document: AnalysisDocument,
    ): Boolean {
        val queue = ArrayDeque<DeclarationDescriptor>()
        candidate.typeConstructor.supertypes.mapNotNullTo(queue) { type ->
            type.constructor.declarationDescriptor
        }
        val visited = mutableSetOf<DeclarationDescriptor>()
        while (queue.isNotEmpty()) {
            val descriptor = queue.removeFirst().original
            if (!visited.add(descriptor)) continue
            if (sameTarget(target, semanticTarget(descriptor, file, document))) return true
            descriptor.typeConstructorOrNull()?.supertypes?.mapNotNullTo(queue) { type ->
                type.constructor.declarationDescriptor
            }
        }
        return false
    }

    private fun DeclarationDescriptor.typeConstructorOrNull() =
        when (this) {
            is ClassDescriptor -> typeConstructor
            is TypeAliasDescriptor -> expandedType.constructor
            else -> null
        }

    private fun overridesTarget(
        candidate: CallableMemberDescriptor,
        target: SemanticTarget,
        file: ParsedKotlinFile,
        document: AnalysisDocument,
    ): Boolean {
        val queue = ArrayDeque<CallableMemberDescriptor>()
        queue.addAll(candidate.overriddenDescriptors)
        val visited = mutableSetOf<DeclarationDescriptor>()
        while (queue.isNotEmpty()) {
            val overridden = queue.removeFirst()
            if (!visited.add(overridden.original)) continue
            val effective = DescriptorToSourceUtils.getEffectiveReferencedDescriptors(overridden)
            if (effective.any { descriptor ->
                    sameTarget(target, semanticTarget(descriptor, file, document))
                }
            ) {
                return true
            }
            queue.addAll(overridden.overriddenDescriptors)
        }
        return false
    }

    private fun declarationDescriptor(
        context: BindingContext,
        declaration: KtDeclaration,
    ): DeclarationDescriptor? =
        when (declaration) {
            is KtPrimaryConstructor,
            is KtSecondaryConstructor,
            -> context[BindingContext.CONSTRUCTOR, declaration]
            is KtPropertyAccessor -> context[BindingContext.PROPERTY_ACCESSOR, declaration]
            is KtParameter -> context[BindingContext.PRIMARY_CONSTRUCTOR_PARAMETER, declaration]
                ?: context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
            else -> context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
        }

    private fun withTargetAnalysis(
        document: AnalysisDocument,
        offset: Int,
        consume: (TargetAnalysis) -> List<SourceDefinition>,
    ): List<SourceDefinition> {
        val localAnalysis = try {
            targetAnalysis(localParser, document, offset, usesGradleModel = false, model = null)
        } catch (_: Exception) {
            null
        }
        if (localAnalysis != null && localAnalysis.targets.isNotEmpty()) {
            return consume(localAnalysis)
        }

        val script = Path.of(URI.create(document.uri))
        val model = modelProvider.modelFor(script)
        return withPinnedParser(document.fileName, model) { parser ->
            val analysis = targetAnalysis(parser, document, offset, usesGradleModel = true, model = model)
            if (analysis.targets.isEmpty()) emptyList() else consume(analysis)
        }
    }

    private fun targetAnalysis(
        parser: KotlinAstParser,
        document: AnalysisDocument,
        offset: Int,
        usesGradleModel: Boolean,
        model: GradleKotlinDslModel?,
    ): TargetAnalysis {
        val parsedFile = parser.parse(document.fileName, document.text)
        val context = parser.bindingContext(parsedFile)
        val targets = semanticDescriptorsAt(parsedFile, context, offset)
            .map { descriptor -> semanticTarget(descriptor, parsedFile, document) }
            .distinctBy { target -> target.source ?: target.descriptor.original }
        return TargetAnalysis(usesGradleModel, parser, model, parsedFile, document, targets)
    }

    private fun semanticDescriptorsAt(
        file: ParsedKotlinFile,
        context: BindingContext,
        offset: Int,
    ): List<DeclarationDescriptor> {
        semanticDeclarationAt(file, offset)?.let { declaration ->
            return listOfNotNull(declarationDescriptor(context, declaration))
        }
        arrayAccessAt(file, offset)?.let { reference ->
            return arrayAccessDescriptors(context, reference)
        }
        val reference = referenceAt(file, offset) ?: return emptyList()
        return referenceDescriptors(context, reference)
    }

    private fun analyzeWorkspaceDocument(
        document: AnalysisDocument,
        usesGradleModel: Boolean,
        consume: (ParsedKotlinFile, BindingContext) -> Unit,
    ) {
        val parser = if (usesGradleModel) {
            val script = Path.of(URI.create(document.uri))
            parserFor(document.fileName, modelProvider.modelFor(script))
        } else {
            localParser
        }
        val parsedFile = parser.parse(document.fileName, document.text)
        consume(parsedFile, parser.bindingContext(parsedFile))
    }

    private fun semanticTarget(
        descriptor: DeclarationDescriptor,
        file: ParsedKotlinFile,
        document: AnalysisDocument,
    ): SemanticTarget {
        val declaration = when (descriptor) {
            is PropertyAccessorDescriptor -> descriptor.correspondingProperty.original
            else -> descriptor.original
        }
        val sourceElement = DescriptorToSourceUtils.getSourceFromDescriptor(declaration)
            ?.takeIf { source -> source.containingFile === file.psi }
        val source = sourceElement?.let { element ->
            val range = declarationSelectionRange(element)
            val definition = SourceDefinition(
                document.uri,
                document.text,
                range.startOffset,
                range.endOffset,
            )
            SourceDeclaration(
                uri = document.uri,
                role = declarationRole(declaration),
                startOffset = range.startOffset,
                endOffset = range.endOffset,
                definition = definition,
            )
        }
        return SemanticTarget(declaration, source)
    }

    private fun sameTarget(first: SemanticTarget, second: SemanticTarget): Boolean {
        if (first.source != null || second.source != null) return first.source == second.source
        val firstDescriptor = first.descriptor
        val secondDescriptor = second.descriptor
        if (firstDescriptor is TypeAliasDescriptor && secondDescriptor is TypeAliasDescriptor) {
            if (CompilerDeclarationIdentity.from(firstDescriptor) != CompilerDeclarationIdentity.from(secondDescriptor)) {
                return false
            }
            val firstOrigin = firstDescriptor.kotlinBinaryOrigin()
            val secondOrigin = secondDescriptor.kotlinBinaryOrigin()
            return if (firstOrigin != null || secondOrigin != null) {
                firstOrigin != null && firstOrigin == secondOrigin
            } else {
                firstDescriptor == secondDescriptor
            }
        }
        return runCatching {
            DescriptorEquivalenceForOverrides.areEquivalent(
                firstDescriptor,
                secondDescriptor,
                allowCopiesFromTheSameDeclaration = true,
                distinguishExpectsAndNonExpects = true,
            )
        }.getOrDefault(false)
    }

    private fun declarationRole(descriptor: DeclarationDescriptor): DeclarationRole =
        when (descriptor) {
            is ConstructorDescriptor -> DeclarationRole.CONSTRUCTOR
            is ClassDescriptor -> DeclarationRole.CLASS
            is TypeAliasDescriptor -> DeclarationRole.TYPE_ALIAS
            is TypeParameterDescriptor -> DeclarationRole.TYPE_PARAMETER
            is VariableDescriptor -> DeclarationRole.VARIABLE
            is CallableDescriptor -> DeclarationRole.CALLABLE
            else -> DeclarationRole.OTHER
        }

    private fun localTypeDefinitions(
        document: AnalysisDocument,
        file: ParsedKotlinFile,
        offset: Int,
    ): List<SourceDefinition> = runCatching {
        typeDescriptorsAt(localParser, file, offset).mapNotNull { descriptor ->
            DescriptorToSourceUtils.descriptorToDeclaration(descriptor)
                ?.takeIf { declaration -> declaration.containingFile === file.psi }
                ?.let { declaration ->
                    val named = declaration as? KtNamedDeclaration ?: return@let null
                    sourceDefinition(document, named)
                }
        }
    }.getOrDefault(emptyList())

    private fun typeDescriptorsAt(
        parser: KotlinAstParser,
        file: ParsedKotlinFile,
        offset: Int,
    ): List<DeclarationDescriptor> {
        val context = parser.bindingContext(file)
        val type = typeReferenceAt(file, offset)?.let { reference ->
            context[BindingContext.ABBREVIATED_TYPE, reference]
                ?: context[BindingContext.TYPE, reference]
        } ?: expressionTypeAt(file, context, offset)
            ?: declarationAt(file, offset)
                ?.let { declaration -> context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration] }
                ?.let(::typeOf)
        return listOfNotNull(type?.let(::classifierOf))
    }

    private fun typeOf(descriptor: DeclarationDescriptor): KotlinType? =
        when (descriptor) {
            is PropertyAccessorDescriptor -> descriptor.correspondingProperty.type
            is ConstructorDescriptor -> descriptor.constructedClass.defaultType
            is ClassDescriptor -> descriptor.defaultType
            is TypeAliasDescriptor -> descriptor.expandedType
            is TypeParameterDescriptor -> descriptor.defaultType
            is VariableDescriptor -> descriptor.type
            is ReceiverParameterDescriptor -> descriptor.type
            is CallableDescriptor -> descriptor.returnType
            else -> null
        }

    private fun classifierOf(type: KotlinType): DeclarationDescriptor? {
        if (type.isError) return null
        val descriptor = type.constructor.declarationDescriptor ?: return null
        return if (descriptor is TypeAliasDescriptor) {
            classifierOf(descriptor.expandedType)
        } else {
            descriptor
        }
    }

    private fun expressionTypeAt(
        file: ParsedKotlinFile,
        context: BindingContext,
        offset: Int,
    ): KotlinType? = elementsAround(file, offset)
        .flatMap { element ->
            generateSequence(element) { current -> current.parent }
                .filterIsInstance<KtExpression>()
        }
        .filter { expression -> offset in expression.textRange.startOffset..expression.textRange.endOffset }
        .distinct()
        .sortedBy { expression -> expression.textRange.length }
        .firstNotNullOfOrNull { expression ->
            context[BindingContext.EXPRESSION_TYPE_INFO, expression]?.type
        }

    private fun typeReferenceAt(file: ParsedKotlinFile, offset: Int): KtTypeReference? =
        elementsAround(file, offset)
            .mapNotNull { element ->
                PsiTreeUtil.getParentOfType(element, KtTypeReference::class.java, false)
            }
            .filter { reference -> offset in reference.textRange.startOffset..reference.textRange.endOffset }
            .minByOrNull { reference -> reference.textRange.length }

    private fun resolveLocalReference(
        file: ParsedKotlinFile,
        offset: Int,
    ): PsiElement? = runCatching {
        resolveDescriptorsAt(localParser, file, offset)
            .singleOrNull()
            ?.let(DescriptorToSourceUtils::descriptorToDeclaration)
            ?.takeIf { declaration -> declaration.containingFile === file.psi }
    }.getOrNull()

    private fun resolveDescriptorsAt(
        parser: KotlinAstParser,
        file: ParsedKotlinFile,
        offset: Int,
    ): List<DeclarationDescriptor> {
        val context = parser.bindingContext(file)
        arrayAccessAt(file, offset)?.let { reference ->
            return arrayAccessDescriptors(context, reference)
        }
        val reference = referenceAt(file, offset) ?: return emptyList()
        return referenceDescriptors(context, reference)
    }

    private fun referenceDescriptors(
        context: BindingContext,
        reference: KtSimpleNameExpression,
    ): List<DeclarationDescriptor> {
        val constructor = reference.getResolvedCall(context)?.resultingDescriptor as? ConstructorDescriptor
        val direct = context[BindingContext.REFERENCE_TARGET, reference]
        val referenced = constructor?.let(::listOf)
            ?: direct?.let(::listOf)
            ?: context[BindingContext.AMBIGUOUS_REFERENCE_TARGET, reference].orEmpty()
        return effectiveDescriptors(referenced)
    }

    private fun arrayAccessDescriptors(
        context: BindingContext,
        reference: KtArrayAccessExpression,
    ): List<DeclarationDescriptor> =
        effectiveDescriptors(
            listOfNotNull(
                reference.getResolvedCall(context)?.resultingDescriptor,
                context[BindingContext.INDEXED_LVALUE_GET, reference]?.resultingDescriptor,
                context[BindingContext.INDEXED_LVALUE_SET, reference]?.resultingDescriptor,
            ),
        )

    private fun effectiveDescriptors(descriptors: Collection<DeclarationDescriptor>): List<DeclarationDescriptor> =
        descriptors.flatMap(DescriptorToSourceUtils::getEffectiveReferencedDescriptors)
            .distinctBy { descriptor -> descriptor.original }

    private fun referenceOccurrences(
        file: ParsedKotlinFile,
        context: BindingContext,
    ): Sequence<ReferenceOccurrence> = sequence {
        PsiTreeUtil.collectElementsOfType(file.psi, KtSimpleNameExpression::class.java)
            .forEach { reference ->
                yield(ReferenceOccurrence(reference.textRange, referenceDescriptors(context, reference)))
            }
        PsiTreeUtil.collectElementsOfType(file.psi, KtArrayAccessExpression::class.java)
            .forEach { reference ->
                val range = reference.leftBracket?.textRange ?: return@forEach
                yield(ReferenceOccurrence(range, arrayAccessDescriptors(context, reference)))
            }
    }

    private fun hoverRangeAt(file: ParsedKotlinFile, offset: Int): TextRange? =
        semanticDeclarationAt(file, offset)?.let(::declarationIdentifierRange)
            ?: arrayAccessAt(file, offset)?.let { reference ->
                listOfNotNull(reference.leftBracket, reference.rightBracket)
                    .firstOrNull { bracket -> offset in bracket.textRange.startOffset..bracket.textRange.endOffset }
                    ?.textRange
            }
            ?: referenceAt(file, offset)?.textRange

    private fun arrayAccessAt(file: ParsedKotlinFile, offset: Int): KtArrayAccessExpression? =
        elementsAround(file, offset)
            .mapNotNull { element ->
                PsiTreeUtil.getParentOfType(element, KtArrayAccessExpression::class.java, false)
            }
            .filter { reference ->
                listOfNotNull(reference.leftBracket, reference.rightBracket)
                    .any { bracket -> offset in bracket.textRange.startOffset..bracket.textRange.endOffset }
            }
            .minByOrNull { reference -> reference.textRange.length }

    private fun referenceAt(file: ParsedKotlinFile, offset: Int): KtSimpleNameExpression? =
        elementsAround(file, offset)
            .mapNotNull { element ->
                PsiTreeUtil.getParentOfType(element, KtSimpleNameExpression::class.java, false)
            }
            .filter { containsOffset(it, offset) }
            .minByOrNull { it.textRange.length }

    private fun semanticDeclarationAt(file: ParsedKotlinFile, offset: Int): KtDeclaration? =
        elementsAround(file, offset)
            .flatMap { element ->
                generateSequence(element) { current -> current.parent }
                    .filterIsInstance<KtDeclaration>()
            }
            .distinct()
            .filter { declaration ->
                declarationIdentifierRange(declaration)?.let { range ->
                    offset in range.startOffset..range.endOffset
                } == true
            }
            .minByOrNull { declaration -> declaration.textRange.length }

    private fun declarationAt(file: ParsedKotlinFile, offset: Int): KtNamedDeclaration? =
        elementsAround(file, offset)
            .mapNotNull { element ->
                PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
            }
            .filter { declaration ->
                declaration.nameIdentifier?.textRange?.let { range ->
                    offset in range.startOffset..range.endOffset
                } == true
            }
            .minByOrNull { it.textRange.length }

    private fun declarationSelectionRange(declaration: PsiElement): TextRange =
        declarationIdentifierRange(declaration) ?: declaration.textRange

    private fun declarationIdentifierRange(declaration: PsiElement): TextRange? =
        when (declaration) {
            is KtSecondaryConstructor -> declaration.getConstructorKeyword().textRange
            is KtPrimaryConstructor -> declaration.getConstructorKeyword()?.textRange
                ?: declaration.getContainingClassOrObject().nameIdentifier?.textRange
            is KtPropertyAccessor -> declaration.namePlaceholder.textRange
            is KtClassOrObject -> declaration.nameIdentifier?.textRange
                ?: declaration.getDeclarationKeyword()?.textRange
            is KtNamedDeclaration -> declaration.nameIdentifier?.textRange
            else -> null
        }

    private fun elementsAround(file: ParsedKotlinFile, offset: Int): Sequence<PsiElement> {
        val textLength = file.psi.textLength
        return sequenceOf(offset, offset - 1)
            .distinct()
            .filter { it in 0 until textLength }
            .mapNotNull(file.psi::findElementAt)
    }

    private fun containsOffset(reference: KtSimpleNameExpression, offset: Int): Boolean =
        offset in reference.textRange.startOffset..reference.textRange.endOffset

    @Synchronized
    private fun parserFor(fileName: String, model: GradleKotlinDslModel): KotlinAstParser {
        val key = parserKey(fileName, model)
        pinnedModelParsers[key]?.let { return it.parser }
        modelParsers[key]?.let { return it }
        return newParser(key).also { parser ->
            modelParsers[key] = parser
            evictModelParsers()
        }
    }

    private fun <T> withPinnedParser(
        fileName: String,
        model: GradleKotlinDslModel,
        consume: (KotlinAstParser) -> T,
    ): T {
        val key = parserKey(fileName, model)
        val parser = synchronized(this) {
            pinnedModelParsers[key]?.let { pinned ->
                pinned.uses += 1
                pinned.parser
            } ?: (modelParsers.remove(key) ?: newParser(key)).also { acquired ->
                pinnedModelParsers[key] = PinnedParser(acquired, 1)
            }
        }
        return try {
            consume(parser)
        } finally {
            synchronized(this) {
                val pinned = checkNotNull(pinnedModelParsers[key])
                pinned.uses -= 1
                if (pinned.uses == 0) {
                    pinnedModelParsers.remove(key)
                    if (closed.get()) {
                        pinned.parser.close()
                    } else {
                        modelParsers[key] = pinned.parser
                        evictModelParsers()
                    }
                }
            }
        }
    }

    private fun parserKey(fileName: String, model: GradleKotlinDslModel): ParserKey {
        val template = KotlinGradleScriptTemplate.forFile(fileName)
        return ParserKey(
            classPath = model.classPath.map { it.toAbsolutePath().normalize() },
            implicitImports = model.implicitImports,
            baseClassName = template.className,
            implicitReceiverClassName = template.implicitReceiverClassName,
            modelGeneration = model.generation,
        )
    }

    private fun newParser(key: ParserKey): KotlinAstParser =
        KotlinAstParser(
            KotlinScriptAnalysisContext(
                classPath = key.classPath,
                implicitImports = key.implicitImports,
                baseClassName = key.baseClassName,
                implicitReceiverClassName = key.implicitReceiverClassName,
            ),
        )

    private fun evictModelParsers() {
        while (modelParsers.size > MAXIMUM_MODEL_PARSERS) {
            val eldest = modelParsers.entries.iterator().next()
            modelParsers.remove(eldest.key)
            eldest.value.close()
        }
    }

    private fun sourceDefinition(
        document: AnalysisDocument,
        declaration: PsiElement,
    ): SourceDefinition {
        val range = declarationSelectionRange(declaration)
        return SourceDefinition(
            document.uri,
            document.text,
            range.startOffset,
            range.endOffset,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            localParser.close()
            synchronized(this) {
                (modelParsers.values + pinnedModelParsers.values.map(PinnedParser::parser))
                    .toSet()
                    .forEach(KotlinAstParser::close)
                modelParsers.clear()
                pinnedModelParsers.clear()
            }
        }
    }

    private data class ResolvedHoverSource(
        val definition: SourceDefinition,
        val parser: KotlinAstParser,
    )

    private data class ReferenceOccurrence(
        val range: TextRange,
        val descriptors: List<DeclarationDescriptor>,
    )

    private data class TargetAnalysis(
        val usesGradleModel: Boolean,
        val parser: KotlinAstParser,
        val model: GradleKotlinDslModel?,
        val file: ParsedKotlinFile,
        val document: AnalysisDocument,
        val targets: List<SemanticTarget>,
    )

    private sealed interface ImplementationTarget {
        val target: SemanticTarget

        data class Class(override val target: SemanticTarget) : ImplementationTarget

        data class Callable(override val target: SemanticTarget) : ImplementationTarget
    }

    private data class SemanticTarget(
        val descriptor: DeclarationDescriptor,
        val source: SourceDeclaration?,
    )

    private data class SourceDeclaration(
        val uri: String,
        val role: DeclarationRole,
        val startOffset: Int,
        val endOffset: Int,
        val definition: SourceDefinition,
    )

    private enum class DeclarationRole {
        CLASS,
        CONSTRUCTOR,
        CALLABLE,
        VARIABLE,
        TYPE_ALIAS,
        TYPE_PARAMETER,
        OTHER,
    }

    private data class PinnedParser(
        val parser: KotlinAstParser,
        var uses: Int,
    )

    private data class ParserKey(
        val classPath: List<Path>,
        val implicitImports: List<String>,
        val baseClassName: String,
        val implicitReceiverClassName: String,
        val modelGeneration: String,
    )

    private companion object {
        val HOVER_RENDERER = DescriptorRenderer.FQ_NAMES_IN_TYPES.withOptions {
            withDefinedIn = false
        }
        val HOVER_CONSTRUCTOR_RENDERER = HOVER_RENDERER.withOptions {
            secondaryConstructorsAsPrimary = true
            renderConstructorKeyword = true
        }

        const val MAXIMUM_MODEL_PARSERS = 1
    }
}
