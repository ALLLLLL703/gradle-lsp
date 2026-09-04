@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

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
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isError
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.analysis.KotlinGradleScriptTemplate
import xyz.al.gradlelsp.analysis.KotlinScriptAnalysisContext
import xyz.al.gradlelsp.analysis.ParsedKotlinFile
import xyz.al.gradlelsp.documents.ExternalDocumentStore
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
    private val localParser: KotlinAstParser = KotlinAstParser(),
) : DocumentNavigationEngine {
    private val closed = AtomicBoolean(false)
    private val modelParsers = LinkedHashMap<ParserKey, KotlinAstParser>(8, 0.75f, true)
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
    ): List<DeclarationDescriptor> {
        val context = parser.bindingContext(file)
        val direct = context[BindingContext.REFERENCE_TARGET, reference]
        val referenced = direct?.let(::listOf)
            ?: context[BindingContext.AMBIGUOUS_REFERENCE_TARGET, reference].orEmpty()
        return referenced
            .flatMap(DescriptorToSourceUtils::getEffectiveReferencedDescriptors)
            .distinctBy { descriptor -> CompilerDeclarationIdentity.from(descriptor) }
    }

    private fun referenceAt(file: ParsedKotlinFile, offset: Int): KtNameReferenceExpression? =
        elementsAround(file, offset)
            .mapNotNull { element ->
                PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
            }
            .filter { containsOffset(it, offset) }
            .minByOrNull { it.textRange.length }

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
        val template = KotlinGradleScriptTemplate.forFile(fileName)
        val key = ParserKey(
            classPath = model.classPath.map { it.toAbsolutePath().normalize() },
            implicitImports = model.implicitImports,
            baseClassName = template.className,
            implicitReceiverClassName = template.implicitReceiverClassName,
            modelGeneration = model.generation,
        )
        modelParsers[key]?.let { return it }
        val parser = KotlinAstParser(
            KotlinScriptAnalysisContext(
                classPath = key.classPath,
                implicitImports = key.implicitImports,
                baseClassName = key.baseClassName,
                implicitReceiverClassName = key.implicitReceiverClassName,
            ),
        )
        modelParsers[key] = parser
        if (modelParsers.size > MAXIMUM_MODEL_PARSERS) {
            val eldest = modelParsers.entries.iterator().next()
            modelParsers.remove(eldest.key)
            eldest.value.close()
        }
        return parser
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
                modelParsers.values.forEach(KotlinAstParser::close)
                modelParsers.clear()
            }
        }
    }

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
