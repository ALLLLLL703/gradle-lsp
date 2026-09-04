@file:Suppress("DEPRECATION_ERROR")

package xyz.al.gradlelsp.navigation

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.analysis.ParsedKotlinFile

internal class KotlinFileNavigationEngine(
    private val parser: KotlinAstParser = KotlinAstParser(),
) : DocumentNavigationEngine {
    override fun definitions(document: AnalysisDocument, offset: Int): List<SourceDefinition> {
        val parsedFile = parser.parse(document.fileName, document.text)
        val target = resolveReference(parsedFile, offset) ?: declarationAt(parsedFile, offset) ?: return emptyList()
        if (target.containingFile !== parsedFile.psi) return emptyList()

        val range = target.nameIdentifier?.textRange ?: target.textRange
        return listOf(SourceDefinition(document.uri, range.startOffset, range.endOffset))
    }

    private fun resolveReference(file: ParsedKotlinFile, offset: Int): KtNamedDeclaration? {
        val reference = elementsAround(file, offset)
            .mapNotNull { element ->
                PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
            }
            .filter { containsOffset(it, offset) }
            .minByOrNull { it.textRange.length }
            ?: return null
        val descriptor = parser.bindingContext(file)[BindingContext.REFERENCE_TARGET, reference] ?: return null
        return DescriptorToSourceUtils.descriptorToDeclaration(descriptor) as? KtNamedDeclaration
    }

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

    override fun close() = parser.close()
}
