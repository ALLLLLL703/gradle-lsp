package xyz.al.gradlelsp.symbols

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeAlias
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser

/** Kotlin PSI structure view following IntelliJ's declaration filtering and ownership rules. */
internal class KotlinDocumentSymbolEngine(
    private val parser: KotlinAstParser = KotlinAstParser(),
) : DocumentSymbolEngine {
    override fun symbols(document: AnalysisDocument): List<SourceDocumentSymbol> {
        val file = parser.parse(document.fileName, document.text).psi
        val declarations = file.script?.declarations ?: file.declarations
        return declarations.mapNotNull { declaration -> symbol(declaration, isMember = false) }
    }

    private fun symbol(
        declaration: KtDeclaration,
        isMember: Boolean,
    ): SourceDocumentSymbol? {
        if (declaration is KtParameter && !declaration.hasValOrVar()) return null

        val nameAndSelection = when (declaration) {
            is KtSecondaryConstructor -> "constructor" to declaration.getConstructorKeyword().textRange
            is KtNamedDeclaration -> {
                val name = declaration.name ?: return null
                val identifier = declaration.nameIdentifier ?: return null
                name to identifier.textRange
            }
            else -> return null
        }
        val range = declaration.textRange
        val selection = nameAndSelection.second
        if (selection.startOffset < range.startOffset || selection.endOffset > range.endOffset) return null

        return SourceDocumentSymbol(
            name = nameAndSelection.first,
            kind = kind(declaration, isMember) ?: return null,
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            selectionStartOffset = selection.startOffset,
            selectionEndOffset = selection.endOffset,
            children = childDeclarations(declaration).mapNotNull { child ->
                symbol(child, isMember = declaration is KtClassOrObject)
            },
        )
    }

    private fun childDeclarations(declaration: KtDeclaration): List<KtDeclaration> =
        when (declaration) {
            is KtClass -> declaration.primaryConstructorParameters.filter(KtParameter::hasValOrVar) +
                declaration.declarations
            is KtClassOrObject -> declaration.declarations
            else -> emptyList()
        }

    private fun kind(declaration: KtDeclaration, isMember: Boolean): SourceSymbolKind? =
        when (declaration) {
            is KtEnumEntry -> SourceSymbolKind.ENUM_MEMBER
            is KtObjectDeclaration -> SourceSymbolKind.OBJECT
            is KtClass -> when {
                declaration.isInterface() -> SourceSymbolKind.INTERFACE
                declaration.isEnum() -> SourceSymbolKind.ENUM
                else -> SourceSymbolKind.CLASS
            }
            is KtSecondaryConstructor -> SourceSymbolKind.CONSTRUCTOR
            is KtNamedFunction -> if (isMember) SourceSymbolKind.METHOD else SourceSymbolKind.FUNCTION
            is KtParameter -> SourceSymbolKind.PROPERTY
            is KtProperty -> when {
                declaration.hasModifier(KtTokens.CONST_KEYWORD) -> SourceSymbolKind.CONSTANT
                isMember -> SourceSymbolKind.PROPERTY
                else -> SourceSymbolKind.VARIABLE
            }
            is KtTypeAlias -> SourceSymbolKind.TYPE_ALIAS
            else -> null
        }

    override fun close() = parser.close()
}
