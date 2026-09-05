package xyz.al.gradlelsp.completion

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.render
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtScriptInitializer
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser

/** Import context is recovered from compiler input with a completion identifier, as in IntelliJ. */
internal object KotlinImportCompletion {
    fun context(parser: KotlinAstParser, document: AnalysisDocument, offset: Int): ImportCompletionContext? {
        if (offset !in 0..document.text.length) return null
        val input = document.text.substring(0, offset) + COMPLETION_IDENTIFIER + document.text.substring(offset)
        val file = parser.parse(document.fileName, input).psi
        val leaf = file.findElementAt(offset) ?: return null
        val reference = PsiTreeUtil.getParentOfType(leaf, KtSimpleNameExpression::class.java, false) ?: return null
        val identifier = reference.getReferencedNameElement()
        val range = identifier.textRange
        if (offset < range.startOffset || offset + COMPLETION_IDENTIFIER.length > range.endOffset) return null
        val name = reference.getReferencedName()
        // The compiler removes the pair of backticks from escaped identifiers.
        val quotingWidth = (identifier.textLength - name.length) / 2
        val prefixLength = offset - range.startOffset - quotingWidth
        if (prefixLength < 0) return null
        val prefix = name.take(prefixLength)
        val endOffset = range.endOffset - COMPLETION_IDENTIFIER.length
        val directive = PsiTreeUtil.getParentOfType(reference, KtImportDirective::class.java)
        if (directive == null) {
            if (quotingWidth != 0 || !"import".startsWith(prefix)) return null
            if ((reference.parent as? KtScriptInitializer)?.body !== reference) return null
            val previous = PsiTreeUtil.prevLeaf(reference, true)
            if (previous != null && previous !is PsiWhiteSpace && previous !is PsiComment &&
                previous.node.elementType != KtTokens.SEMICOLON) return null
            // Ask the parser whether an import is legal here, including header ordering.
            val keywordInput = document.text.substring(0, range.startOffset) +
                "import $COMPLETION_IDENTIFIER\n" + document.text.substring(endOffset)
            val keywordFile = parser.parse(document.fileName, keywordInput).psi
            if (keywordFile.importDirectives.none { it.textRange.startOffset == range.startOffset }) return null
            return ImportKeywordContext(range.startOffset, endOffset)
        }
        if (directive !in file.importDirectives) return null
        if (!PsiTreeUtil.isAncestor(directive.importedReference, reference, false)) return null
        val parentExpression = reference.parent as? KtDotQualifiedExpression
        val qualifier = if (parentExpression?.selectorExpression === reference) {
            qualifiedName(parentExpression.receiverExpression) ?: return null
        } else {
            FqName.ROOT
        }
        val completedExpression = if (parentExpression?.selectorExpression === reference) parentExpression else reference
        val followingExpression = completedExpression.parent as? KtDotQualifiedExpression
        val hasFollowingDot = followingExpression?.receiverExpression === completedExpression ||
            (directive.isAllUnder && completedExpression === directive.importedReference)

        return ImportPackageContext(
            qualifier = qualifier,
            sourcePackage = file.packageFqName,
            prefix = prefix,
            startOffset = range.startOffset,
            endOffset = endOffset,
            appendDot = !hasFollowingDot,
        )
    }

    fun keyword(context: ImportKeywordContext): SourceCompletions = SourceCompletions(
        listOf(SourceCompletionItem("import", "import", "import ", context.startOffset, context.endOffset, SourceCompletionKind.KEYWORD, sortText = "050:keyword:import")),
    )

    fun complete(context: ImportPackageContext, packages: List<FqName>, declarations: List<DeclarationDescriptor>): SourceCompletions {
        val packageItems = packages.asSequence()
            .filter { name -> !name.isRoot && name.parent() == context.qualifier }
            .filter { name -> name.shortName().asString().startsWith(context.prefix, ignoreCase = true) }
            .map { name ->
                val rendered = name.shortName().render()
                SourceCompletionItem(
                    name = rendered,
                    qualifiedName = name.asString(),
                    insertText = rendered + if (context.appendDot) "." else "",
                    startOffset = context.startOffset,
                    endOffset = context.endOffset,
                )
            }
        val declarationItems = declarations.asSequence().map { descriptor ->
            val item = KotlinSemanticCompletion.item(descriptor, context.startOffset, context.endOffset)
            // Preserve the existing concise, qualified-name presentation for imported classes.
            if (descriptor is ClassDescriptor) item.copy(detail = null) else item
        }
        val matches = (packageItems + declarationItems)
            .distinctBy { Triple(it.qualifiedName, it.kind, it.detail) }
            .sortedWith(compareBy<SourceCompletionItem> { it.qualifiedName }.thenBy { it.kind }.thenBy { it.detail })
            .take(MAXIMUM_ITEMS + 1)
            .toList()
        return SourceCompletions(matches.take(MAXIMUM_ITEMS), isIncomplete = matches.size > MAXIMUM_ITEMS)
    }

    private fun qualifiedName(expression: KtExpression): FqName? = when (expression) {
        is KtSimpleNameExpression -> FqName.topLevel(expression.getReferencedNameAsName())
        is KtDotQualifiedExpression -> {
            val receiver = qualifiedName(expression.receiverExpression)
            val selector = expression.selectorExpression as? KtSimpleNameExpression
            if (receiver == null || selector == null) null else receiver.child(selector.getReferencedNameAsName())
        }
        else -> null
    }

    private const val COMPLETION_IDENTIFIER = "IntellijIdeaRulezzz"
    private const val MAXIMUM_ITEMS = 128
}

internal sealed interface ImportCompletionContext

internal data class ImportKeywordContext(val startOffset: Int, val endOffset: Int) : ImportCompletionContext

internal data class ImportPackageContext(
    val qualifier: FqName,
    val sourcePackage: FqName,
    val prefix: String,
    val startOffset: Int,
    val endOffset: Int,
    val appendDot: Boolean,
) : ImportCompletionContext
