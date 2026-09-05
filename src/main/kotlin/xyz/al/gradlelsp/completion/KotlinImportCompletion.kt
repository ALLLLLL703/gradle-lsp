package xyz.al.gradlelsp.completion

import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.render
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser

/** Import context is recovered from compiler input with a completion identifier, as in IntelliJ. */
internal object KotlinImportCompletion {
    fun context(parser: KotlinAstParser, document: AnalysisDocument, offset: Int): ImportPackageContext? {
        if (offset !in 0..document.text.length) return null
        val input = document.text.substring(0, offset) + COMPLETION_IDENTIFIER + document.text.substring(offset)
        val file = parser.parse(document.fileName, input).psi
        val leaf = file.findElementAt(offset) ?: return null
        val reference = PsiTreeUtil.getParentOfType(leaf, KtSimpleNameExpression::class.java, false) ?: return null
        val directive = PsiTreeUtil.getParentOfType(reference, KtImportDirective::class.java) ?: return null
        if (directive !in file.importDirectives) return null
        if (!PsiTreeUtil.isAncestor(directive.importedReference, reference, false)) return null

        val identifier = reference.getReferencedNameElement()
        val range = identifier.textRange
        if (offset < range.startOffset || offset + COMPLETION_IDENTIFIER.length > range.endOffset) return null
        val name = reference.getReferencedName()
        // The compiler removes the pair of backticks from escaped identifiers.
        val quotingWidth = (identifier.textLength - name.length) / 2
        val prefixLength = offset - range.startOffset - quotingWidth
        if (prefixLength < 0) return null
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
            prefix = name.take(prefixLength),
            startOffset = range.startOffset,
            endOffset = range.endOffset - COMPLETION_IDENTIFIER.length,
            appendDot = !hasFollowingDot,
        )
    }

    fun complete(context: ImportPackageContext, packages: List<FqName>): SourceCompletions {
        val matches = packages.asSequence()
            .filter { name -> !name.isRoot && name.parent() == context.qualifier }
            .filter { name -> name.shortName().asString().startsWith(context.prefix, ignoreCase = true) }
            .distinct()
            .sortedBy(FqName::asString)
            .take(MAXIMUM_ITEMS + 1)
            .toList()
        return SourceCompletions(
            items = matches.take(MAXIMUM_ITEMS).map { name ->
                val rendered = name.shortName().render()
                SourcePackageCompletion(
                    name = rendered,
                    qualifiedName = name.asString(),
                    insertText = rendered + if (context.appendDot) "." else "",
                    startOffset = context.startOffset,
                    endOffset = context.endOffset,
                )
            },
            isIncomplete = matches.size > MAXIMUM_ITEMS,
        )
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

internal data class ImportPackageContext(
    val qualifier: FqName,
    val prefix: String,
    val startOffset: Int,
    val endOffset: Int,
    val appendDot: Boolean,
)
