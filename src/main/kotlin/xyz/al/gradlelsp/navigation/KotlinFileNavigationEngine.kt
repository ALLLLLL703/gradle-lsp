@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeReference
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
import xyz.al.gradlelsp.documents.DocumentStore
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.documents.GradleWorkspaceDocumentSource
import xyz.al.gradlelsp.documents.WorkspaceDocumentSource
import xyz.al.gradlelsp.gradle.GradleKotlinDslModel
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelLoader
import xyz.al.gradlelsp.gradle.GradleKotlinDslModelProvider
import java.net.URI
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class KotlinFileNavigationEngine(
    private val modelProvider: GradleKotlinDslModelProvider = GradleKotlinDslModelLoader(),
    externalDocuments: ExternalDocumentStore = ExternalDocumentStore(),
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
        declarationAt(localFile, offset)?.let { declaration ->
            return listOf(sourceDefinition(document, declaration))
        }
        resolveLocalReference(localFile, offset)?.let { declaration ->
            return listOf(sourceDefinition(document, declaration))
        }

        val script = Path.of(URI.create(document.uri))
        val model = modelProvider.modelFor(script)
        val parser = parserFor(document.fileName, model)
        val parsedFile = parser.parse(document.fileName, document.text)
        val reference = referenceAt(parsedFile, offset) ?: return emptyList()
        val descriptors = resolveDescriptors(parser, parsedFile, reference)
        return descriptors.flatMap { descriptor ->
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
            val sourceUris = analysis.targets.mapNotNullTo(mutableSetOf()) { target ->
                target.source?.uri
            }
            val visitedSourceUris = mutableSetOf<String>()
            workspaceDocuments.forEachDocument(document) { candidateDocument ->
                if (candidateDocument.uri in sourceUris) visitedSourceUris += candidateDocument.uri
                try {
                    analyzeWorkspaceDocument(candidateDocument, analysis.usesGradleModel) { parsedFile, context ->
                        PsiTreeUtil.collectElementsOfType(
                            parsedFile.psi,
                            KtNameReferenceExpression::class.java,
                        ).forEach { reference ->
                            val matches = referenceDescriptors(context, reference)
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
                                val range = reference.textRange
                                references += SourceDefinition(
                                    candidateDocument.uri,
                                    candidateDocument.text,
                                    range.startOffset,
                                    range.endOffset,
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // One invalid or unavailable script model must not abort the workspace scan.
                }
            }
            if (includeDeclaration) {
                analysis.targets.mapNotNullTo(references) { target ->
                    target.source?.takeIf { source -> source.uri in visitedSourceUris }?.definition
                }
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

    private fun withTargetAnalysis(
        document: AnalysisDocument,
        offset: Int,
        consume: (TargetAnalysis) -> List<SourceDefinition>,
    ): List<SourceDefinition> {
        val localAnalysis = try {
            targetAnalysis(localParser, document, offset, usesGradleModel = false)
        } catch (_: Exception) {
            null
        }
        if (localAnalysis != null && localAnalysis.targets.isNotEmpty()) {
            return consume(localAnalysis)
        }

        val script = Path.of(URI.create(document.uri))
        val model = modelProvider.modelFor(script)
        return withPinnedParser(document.fileName, model) { parser ->
            val analysis = targetAnalysis(parser, document, offset, usesGradleModel = true)
            if (analysis.targets.isEmpty()) emptyList() else consume(analysis)
        }
    }

    private fun targetAnalysis(
        parser: KotlinAstParser,
        document: AnalysisDocument,
        offset: Int,
        usesGradleModel: Boolean,
    ): TargetAnalysis {
        val parsedFile = parser.parse(document.fileName, document.text)
        val context = parser.bindingContext(parsedFile)
        val targets = semanticDescriptorsAt(parsedFile, context, offset)
            .map { descriptor -> semanticTarget(descriptor, parsedFile, document) }
            .distinctBy { target -> target.source ?: target.descriptor.original }
        return TargetAnalysis(usesGradleModel, targets)
    }

    private fun semanticDescriptorsAt(
        file: ParsedKotlinFile,
        context: BindingContext,
        offset: Int,
    ): List<DeclarationDescriptor> {
        semanticDeclarationAt(file, offset)?.let { declaration ->
            val descriptor = when (declaration) {
                is KtPrimaryConstructor,
                is KtSecondaryConstructor,
                -> context[BindingContext.CONSTRUCTOR, declaration]
                is KtPropertyAccessor -> context[BindingContext.PROPERTY_ACCESSOR, declaration]
                else -> context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
            }
            return listOfNotNull(descriptor)
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
            return org.jetbrains.kotlin.resolve.DescriptorUtils.getFqNameSafe(firstDescriptor) ==
                org.jetbrains.kotlin.resolve.DescriptorUtils.getFqNameSafe(secondDescriptor)
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
    ): KtNamedDeclaration? = runCatching {
        val reference = referenceAt(file, offset) ?: return@runCatching null
        resolveDescriptors(localParser, file, reference)
            .singleOrNull()
            ?.let(DescriptorToSourceUtils::descriptorToDeclaration)
            ?.takeIf { declaration -> declaration.containingFile === file.psi }
            as? KtNamedDeclaration
    }.getOrNull()

    private fun resolveDescriptors(
        parser: KotlinAstParser,
        file: ParsedKotlinFile,
        reference: KtNameReferenceExpression,
    ): List<DeclarationDescriptor> =
        referenceDescriptors(parser.bindingContext(file), reference)

    private fun referenceDescriptors(
        context: BindingContext,
        reference: KtNameReferenceExpression,
    ): List<DeclarationDescriptor> {
        val constructor = reference.getResolvedCall(context)?.resultingDescriptor as? ConstructorDescriptor
        val direct = context[BindingContext.REFERENCE_TARGET, reference]
        val referenced = constructor?.let(::listOf)
            ?: direct?.let(::listOf)
            ?: context[BindingContext.AMBIGUOUS_REFERENCE_TARGET, reference].orEmpty()
        return referenced
            .flatMap(DescriptorToSourceUtils::getEffectiveReferencedDescriptors)
            .distinctBy { descriptor -> descriptor.original }
    }

    private fun referenceAt(file: ParsedKotlinFile, offset: Int): KtNameReferenceExpression? =
        elementsAround(file, offset)
            .mapNotNull { element ->
                PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
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

    private fun containsOffset(reference: KtNameReferenceExpression, offset: Int): Boolean =
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
        declaration: KtNamedDeclaration,
    ): SourceDefinition {
        val range = declaration.nameIdentifier?.textRange ?: declaration.textRange
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

    private data class TargetAnalysis(
        val usesGradleModel: Boolean,
        val targets: List<SemanticTarget>,
    )

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
        const val MAXIMUM_MODEL_PARSERS = 4
    }
}
