package xyz.al.gradlelsp.navigation

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaDocumentedElement
import org.jetbrains.kotlin.com.intellij.psi.javadoc.PsiDocComment
import org.jetbrains.kotlin.com.intellij.psi.javadoc.PsiInlineDocTag
import org.jetbrains.kotlin.kdoc.parser.KDocKnownTag
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.kdoc.psi.impl.KDocSection
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

internal object SourceDocumentation {
    fun kotlin(declaration: KtDeclaration): String? {
        val owner = generateSequence(declaration as PsiElement) { element -> element.parent }
            .filterIsInstance<KtDeclaration>()
            .firstNotNullOfOrNull { candidate ->
                candidate.docComment?.let { comment -> KDocOwner(candidate, comment) }
            } ?: return null
        val section = when {
            declaration is KtPrimaryConstructor && owner.declaration !== declaration ->
                owner.comment.findSectionByTag(KDocKnownTag.CONSTRUCTOR)
                    ?: owner.comment.getDefaultSection()
            declaration is KtSecondaryConstructor && owner.declaration !== declaration ->
                owner.comment.findSectionByTag(KDocKnownTag.CONSTRUCTOR)
                    ?: owner.comment.getDefaultSection()
            declaration is KtParameter && declaration.hasValOrVar() ->
                owner.comment.findSectionByTag(KDocKnownTag.PROPERTY, declaration.name.orEmpty())
                    ?: owner.comment.getDefaultSection()
            else -> owner.comment.getDefaultSection()
        }
        if (section.findTagByName("suppress") != null) return null
        return renderKDocSection(section)
    }

    fun java(declaration: PsiJavaDocumentedElement): String? {
        val comment = declaration.docComment ?: return null
        val blocks = mutableListOf<String>()
        normalizeJavaDoc(renderJavaElements(comment.descriptionElements))
            .takeIf(String::isNotBlank)
            ?.let(blocks::add)
        JAVA_TAG_GROUPS.forEach { group ->
            renderJavaTagGroup(comment, group)?.let(blocks::add)
        }
        return blocks.joinToString("\n\n").takeIf(String::isNotBlank)
    }

    private fun renderKDocSection(section: KDocSection): String? {
        val blocks = mutableListOf<String>()
        section.getContent().trim().takeIf(String::isNotBlank)?.let(blocks::add)
        KDOC_TAG_GROUPS.forEach { group ->
            val tags = group.names.flatMap(section::findTagsByName)
            renderKDocTagGroup(tags, group)?.let(blocks::add)
        }
        return blocks.joinToString("\n\n").takeIf(String::isNotBlank)
    }

    private fun renderKDocTagGroup(tags: List<KDocTag>, group: TagGroup): String? {
        val items = tags.mapNotNull { tag ->
            val subject = tag.getSubjectName()?.takeIf(String::isNotBlank)
            val content = tag.getContent().trim().takeIf(String::isNotBlank)
            renderTagItem(subject, content, group)
        }
        if (items.isEmpty()) return null
        return buildString {
            append("* **").append(group.label).append(":**")
            items.forEach { item -> append("\n  * ").append(item) }
        }
    }

    private fun renderJavaTagGroup(comment: PsiDocComment, group: TagGroup): String? {
        val tags = group.names.flatMap { name -> comment.findTagsByName(name).asList() }
        val items = tags.mapNotNull { tag ->
            val value = tag.valueElement?.takeIf { group.hasSubject || group.subjectIsLink }
            val subject = value?.text?.trim()?.takeIf(String::isNotBlank)
            val content = normalizeJavaDoc(
                renderJavaElements(tag.dataElements.filterNot { element -> element === value }.toTypedArray()),
            ).takeIf(String::isNotBlank)
            renderTagItem(subject, content, group)
        }
        if (items.isEmpty()) return null
        return buildString {
            append("* **").append(group.label).append(":**")
            items.forEach { item -> append("\n  * ").append(item) }
        }
    }

    private fun renderTagItem(
        subject: String?,
        content: String?,
        group: TagGroup,
    ): String? {
        if (subject == null && content == null) return null
        val renderedSubject = subject?.let { name ->
            when {
                group.subjectIsLink -> "[$name]"
                group.hasSubject -> "**${escapeMarkdown(name)}**"
                content == null -> escapeMarkdown(name)
                else -> null
            }
        }
        return listOfNotNull(renderedSubject, content).joinToString(" ").takeIf(String::isNotBlank)
    }

    private fun renderJavaElements(elements: Array<out PsiElement>): String =
        elements.joinToString(separator = "") { element ->
            when (element) {
                is PsiInlineDocTag -> renderJavaInlineTag(element)
                else -> element.text
            }
        }

    private fun renderJavaInlineTag(tag: PsiInlineDocTag): String {
        val value = tag.valueElement
        val subject = value?.text?.trim().orEmpty()
        val detail = renderJavaElements(
            tag.dataElements.filterNot { element -> element === value }.toTypedArray(),
        ).trim()
        val content = detail.ifBlank { subject }
        return when (tag.name) {
            "code" -> "`${content.replace("`", "\\`")}`"
            "literal" -> escapeMarkdown(content)
            "link" -> "`${detail.ifBlank { subject }}`"
            "linkplain" -> detail.ifBlank { subject }
            "value" -> content
            "inheritDoc" -> ""
            else -> content
        }
    }

    private fun normalizeJavaDoc(content: String): String {
        val markdown = content
            .replace("<p>", "\n\n", ignoreCase = true)
            .replace("</p>", "", ignoreCase = true)
            .replace("<br>", "  \n", ignoreCase = true)
            .replace("<br/>", "  \n", ignoreCase = true)
            .replace("<br />", "  \n", ignoreCase = true)
            .replace("<code>", "`", ignoreCase = true)
            .replace("</code>", "`", ignoreCase = true)
            .replace("<b>", "**", ignoreCase = true)
            .replace("</b>", "**", ignoreCase = true)
            .replace("<strong>", "**", ignoreCase = true)
            .replace("</strong>", "**", ignoreCase = true)
            .replace("<em>", "_", ignoreCase = true)
            .replace("</em>", "_", ignoreCase = true)
            .replace("<i>", "_", ignoreCase = true)
            .replace("</i>", "_", ignoreCase = true)
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        val lines = markdown.lines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("*")) trimmed.drop(1).trimStart().trimEnd() else line.trimEnd()
        }
        val first = lines.indexOfFirst(String::isNotBlank)
        if (first < 0) return ""
        val last = lines.indexOfLast(String::isNotBlank)
        return lines.subList(first, last + 1).joinToString("\n")
    }

    private fun escapeMarkdown(value: String): String =
        value.replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("`", "\\`")

    private data class KDocOwner(
        val declaration: KtDeclaration,
        val comment: KDoc,
    )

    private data class TagGroup(
        val names: List<String>,
        val label: String,
        val hasSubject: Boolean = false,
        val subjectIsLink: Boolean = false,
    )

    private val KDOC_TAG_GROUPS = listOf(
        TagGroup(listOf("param"), "Parameters", hasSubject = true),
        TagGroup(listOf("receiver"), "Receiver"),
        TagGroup(listOf("return"), "Returns"),
        TagGroup(listOf("throws", "exception"), "Throws", hasSubject = true),
        TagGroup(listOf("since"), "Since"),
        TagGroup(listOf("author"), "Author"),
        TagGroup(listOf("see"), "See Also", subjectIsLink = true),
        TagGroup(listOf("sample"), "Samples", subjectIsLink = true),
    )

    private val JAVA_TAG_GROUPS = listOf(
        TagGroup(listOf("param"), "Parameters", hasSubject = true),
        TagGroup(listOf("return"), "Returns"),
        TagGroup(listOf("throws", "exception"), "Throws", hasSubject = true),
        TagGroup(listOf("since"), "Since"),
        TagGroup(listOf("author"), "Author"),
        TagGroup(listOf("see"), "See Also", subjectIsLink = true),
    )
}
