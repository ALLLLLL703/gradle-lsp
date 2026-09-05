package xyz.al.gradlelsp.completion

import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.resolve.AnnotationChecker
import org.jetbrains.kotlin.resolve.possibleTargetPredicateMap
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.Compatibility
import org.jetbrains.kotlin.resolve.compatibility
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy
import xyz.al.gradlelsp.analysis.AnalysisDocument
import xyz.al.gradlelsp.analysis.KotlinAstParser

/** Compiler keyword inventory, accepted by reparsing at the caret, not a global keyword bag. */
internal object KotlinKeywordCompletion {
    private const val DUMMY = "IntellijIdeaRulezzz"
    fun complete(parser: KotlinAstParser, document: AnalysisDocument, offset: Int,
        semantic: KotlinCompletionContext?, binding: BindingContext?): List<SourceCompletionItem> {
        if (offset !in 0..document.text.length) return emptyList()
        val original = parser.parse(document.fileName, document.text).psi
        if (original.importDirectives.any { offset in it.textRange.startOffset..it.textRange.endOffset } ||
            original.packageDirective?.takeIf { it.textLength > 0 }?.let { offset in it.textRange.startOffset..it.textRange.endOffset } == true) return emptyList()
        val file = semantic?.file?.psi ?: parser.parse(document.fileName,
            document.text.substring(0, offset) + DUMMY + document.text.substring(offset)).psi
        val leaf = file.findElementAt(offset) ?: return emptyList()
        if (leaf.node.elementType != IDENTIFIER) return emptyList()
        val name = (leaf.parent as? KtSimpleNameExpression)?.getReferencedName() ?: leaf.text
        if (name.length != leaf.textLength) return emptyList() // escaped identifiers are not keywords
        val prefix = name.take(offset - leaf.textRange.startOffset)
        val start = leaf.textRange.startOffset
        val end = leaf.textRange.endOffset - DUMMY.length
        if (end < start) return emptyList()
        val ancestors = generateSequence(leaf.parent) { it.parent }.toList()
        val reference = leaf.parent as? KtSimpleNameExpression
        val selector = (reference?.parent as? KtCallExpression) ?: reference
        // Reject selector contexts before probe parsing can recover an invalid keyword as a sibling.
        if (selector != null && (selector.parent as? KtQualifiedExpression)?.selectorExpression === selector ||
            reference != null && (reference.parent as? KtCallableReferenceExpression)?.callableReference === reference) return emptyList()
        val declarationPosition = reference?.parent is KtScriptInitializer || reference?.parent is KtBlockExpression ||
            ancestors.takeWhile { it !is KtDeclaration }.any { it is KtClassBody || it is KtModifierList }
        if (ancestors.any { it is KtImportDirective || it is KtPackageDirective || it is KtSimpleNameStringTemplateEntry || it is KtValueArgumentName }) return emptyList()
        val scope = if (binding != null) ancestors.filterIsInstance<KtElement>()
            .firstNotNullOfOrNull { binding[BindingContext.LEXICAL_SCOPE, it] } else null
        val receivers = scope?.getImplicitReceiversHierarchy().orEmpty()
        val functionBoundary = ancestors.firstOrNull { it is KtNamedFunction || it is KtFunctionLiteral || it is KtClassOrObject }
        val loop = ancestors.takeWhile { it !is KtNamedFunction && it !is KtFunctionLiteral && it !is KtClassOrObject }
            .any { it is KtLoopExpression }
        return (KEYWORDS.types + SOFT_KEYWORDS.types).filterIsInstance<KtKeywordToken>().distinct()
            .filter { it.value.startsWith(prefix) && it != IMPORT_KEYWORD && it != DYNAMIC_KEYWORD && it != TYPEOF_KEYWORD }
            .filter { token ->
                declarationPosition || !(token == VAL_KEYWORD || token == VAR_KEYWORD || token == CLASS_KEYWORD ||
                    token == INTERFACE_KEYWORD || token == TYPE_ALIAS_KEYWORD ||
                    (token is KtModifierKeywordToken && token != FUN_KEYWORD && token != SUSPEND_KEYWORD && token != IN_KEYWORD && token != OUT_KEYWORD && token != REIFIED_KEYWORD && token != VARARG_KEYWORD && token != NOINLINE_KEYWORD && token != CROSSINLINE_KEYWORD))
            }
            .filter { token -> when (token) {
                FOR_KEYWORD, WHILE_KEYWORD, DO_KEYWORD -> declarationPosition
                RETURN_KEYWORD -> functionBoundary is KtNamedFunction && functionBoundary.hasBlockBody()
                BREAK_KEYWORD, CONTINUE_KEYWORD -> loop
                THIS_KEYWORD -> receivers.isNotEmpty()
                SUPER_KEYWORD -> ancestors.any { it is KtClassOrObject } && receivers.isNotEmpty()
                else -> true
            } }
            .filter { token ->
                val suffix = if (token == SUSPEND_KEYWORD && declarationPosition) " fun completionprobe() {}\n" else continuation(token)
                val probe = parser.parse(document.fileName, document.text.substring(0, start) + token.value + suffix +
                    document.text.substring(end)).psi
                val keyword = probe.findElementAt(start)
                keyword?.node?.elementType == token &&
                    PsiTreeUtil.getParentOfType(keyword, PsiErrorElement::class.java, false) == null &&
                    (token !is KtModifierKeywordToken || token == FUN_KEYWORD || (token == IN_KEYWORD && keyword.parent !is KtModifierList) || validModifier(keyword.parent as? KtModifierList, token)) &&
                    // Recovery can create declarations inside an expression; reject those invalid AST placements.
                    generateSequence(keyword.parent) { it.parent }.none { node ->
                        node is KtDeclaration && node.parent is KtValueArgument
                    }
            }.map { token -> SourceCompletionItem(token.value, token.value, token.value, start, end,
                SourceCompletionKind.KEYWORD, sortText = "000:keyword:${token.value}") }
    }

    private fun validModifier(list: KtModifierList?, token: KtModifierKeywordToken): Boolean {
        if (list == null) return false
        if (list.node.getChildren(org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet.create(token)).size > 1) return false
        val owner = list.parent as? KtModifierListOwner ?: return false
        if (owner is KtTypeReference) return TYPE_MODIFIER_KEYWORDS.contains(token)
        if (owner is KtTypeProjection) return TYPE_ARGUMENT_MODIFIER_KEYWORDS.contains(token)
        val targets = if (owner is KtClassOrObject) KotlinTarget.classActualTargets(
            when {
                owner is KtObjectDeclaration -> ClassKind.OBJECT
                owner is KtClass && owner.isInterface() -> ClassKind.INTERFACE
                owner is KtClass && owner.isEnum() -> ClassKind.ENUM_CLASS
                owner.hasModifier(ANNOTATION_KEYWORD) -> ClassKind.ANNOTATION_CLASS
                else -> ClassKind.CLASS
            }, owner.hasModifier(INNER_KEYWORD), (owner as? KtObjectDeclaration)?.isCompanion() == true,
            owner.parent is KtBlockExpression && owner.parent.parent !is KtScript)
        else AnnotationChecker.getDeclarationSiteActualTargetList(owner, null, BindingContext.EMPTY)
        if (targets.none { possibleTargetPredicateMap[token]?.isAllowed(it, LanguageVersionSettingsImpl.DEFAULT) == true }) return false
        if (MODIFIER_KEYWORDS_ARRAY.any { it != token && list.hasModifier(it) &&
                compatibility(it, token) in setOf(Compatibility.INCOMPATIBLE, Compatibility.REPEATED) }) return false
        val declaration = list.parent
        val local = declaration is KtDeclaration && (declaration.parent is KtBlockExpression && declaration.parent.parent !is KtScript)
        if (local && (VISIBILITY_MODIFIERS.contains(token) || token == OVERRIDE_KEYWORD || token == INNER_KEYWORD || token == COMPANION_KEYWORD)) return false
        if (token == PROTECTED_KEYWORD || token == OVERRIDE_KEYWORD || token == INNER_KEYWORD || token == COMPANION_KEYWORD) {
            if (declaration.parent !is KtClassBody) return false
        }
        return true
    }

    /** Probe continuations are compiler input only; the parser determines whether the token fits. */
    private fun continuation(token: KtKeywordToken): String = when (token) {
        PACKAGE_KEYWORD -> " completionprobe\n"
        VAL_KEYWORD, VAR_KEYWORD -> " completionprobe = 0\n"
        FUN_KEYWORD -> " completionprobe() {}\n"
        CLASS_KEYWORD, INTERFACE_KEYWORD, TYPE_ALIAS_KEYWORD -> if (token == TYPE_ALIAS_KEYWORD) " CompletionProbe = Any\n" else " CompletionProbe {}\n"
        OBJECT_KEYWORD -> " CompletionProbe {}\n"
        IF_KEYWORD, WHILE_KEYWORD -> " (true) {}\n"
        FOR_KEYWORD -> " (completionprobe in emptyList<Any>()) {}\n"
        WHEN_KEYWORD -> " (0) { else -> 0 }\n"
        TRY_KEYWORD -> " {} finally {}\n"
        DO_KEYWORD -> " {} while (true)\n"
        THROW_KEYWORD -> " Exception()"
        IS_KEYWORD, AS_KEYWORD, AS_SAFE, NOT_IS -> " Any"
        IN_KEYWORD, NOT_IN -> " emptyList<Any>()"
        ELSE_KEYWORD -> " {}\n"
        CATCH_KEYWORD -> " (completionprobe: Exception) {}\n"
        FINALLY_KEYWORD, INIT_KEYWORD -> " {}\n"
        CONSTRUCTOR_KEYWORD -> "() {}\n"
        SUSPEND_KEYWORD -> " () -> Unit"
        OUT_KEYWORD -> " Any"
        REIFIED_KEYWORD -> " CompletionProbe"
        VARARG_KEYWORD, NOINLINE_KEYWORD, CROSSINLINE_KEYWORD -> " completionprobe: () -> Unit"
        CONST_KEYWORD -> " val completionprobe = 0\n"
        LATEINIT_KEYWORD -> " var completionprobe: Any\n"
        COMPANION_KEYWORD -> " object {}\n"
        INLINE_KEYWORD, TAILREC_KEYWORD, OPERATOR_KEYWORD, INFIX_KEYWORD, EXTERNAL_KEYWORD, OVERRIDE_KEYWORD -> " fun completionprobe() {}\n"
        is KtModifierKeywordToken -> " class CompletionProbe {}\n"
        else -> " "
    }
}
