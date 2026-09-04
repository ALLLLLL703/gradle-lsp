package xyz.al.gradlelsp.navigation

import org.jd.core.v1.api.printer.Printer
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiEllipsisType
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiPrimitiveType
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import xyz.al.gradlelsp.analysis.KotlinAstParser
import xyz.al.gradlelsp.documents.ExternalDocumentStore
import xyz.al.gradlelsp.gradle.GradleKotlinDslModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal class KotlinExternalSourceResolver(
    private val documents: ExternalDocumentStore,
) {
    private val kotlinDecompiler = KotlinDescriptorStubDecompiler(documents)
    private val javaDecompiler = JavaClassDecompiler(documents)
    private val archiveIndexes = LinkedHashMap<ArchiveIndexKey, SourceArchiveIndex>(64, 0.75f, true)
    fun resolve(
        descriptor: DeclarationDescriptor,
        model: GradleKotlinDslModel,
        parser: KotlinAstParser,
    ): List<SourceDefinition> {
        val identity = CompilerDeclarationIdentity.from(descriptor) ?: return emptyList()
        val packageName = descriptor.packageName() ?: return emptyList()
        val sourceDefinition = sourceUnits(model.sourcePath, packageName)
            .firstNotNullOfOrNull { unit ->
                findDeclaration(unit, packageName, identity, descriptor, parser)
            }
        return if (sourceDefinition != null) {
            listOf(sourceDefinition)
        } else {
            listOfNotNull(
                kotlinDecompiler.decompile(descriptor, parser)
                    ?: javaDecompiler.decompile(descriptor, model, parser),
            )
        }
    }

    private fun findDeclaration(
        unit: SourceUnit,
        packageName: String,
        target: CompilerDeclarationIdentity,
        descriptor: DeclarationDescriptor,
        parser: KotlinAstParser,
    ): SourceDefinition? =
        when (unit.languageId) {
            "kotlin" -> findKotlinDeclaration(unit, packageName, target, parser)
            "java" -> findJavaDeclaration(unit, packageName, descriptor, parser)
            else -> null
        }

    private fun findKotlinDeclaration(
        unit: SourceUnit,
        packageName: String,
        target: CompilerDeclarationIdentity,
        parser: KotlinAstParser,
    ): SourceDefinition? = runCatching {
        val parsed = parser.parse(unit.fileName, unit.text)
        if (parsed.psi.packageFqName.asString() != packageName) return@runCatching null
        val namedCandidates = PsiTreeUtil.collectElementsOfType(parsed.psi, KtDeclaration::class.java)
            .asSequence()
            .filterIsInstance<KtNamedDeclaration>()
            .filter { candidate -> candidate.name == target.fqName.substringAfterLast('.') }
            .toList()
        if (namedCandidates.isEmpty()) return@runCatching null
        val context = parser.bindingContext(parsed)
        val declaration = namedCandidates.firstOrNull { candidate ->
            val candidateDescriptor = context[BindingContext.DECLARATION_TO_DESCRIPTOR, candidate]
            candidateDescriptor != null && CompilerDeclarationIdentity.from(candidateDescriptor) == target
        }
            ?: return@runCatching null
        sourceDefinition(unit, declaration.nameIdentifier?.textRange ?: return@runCatching null)
    }.getOrNull()

    private fun findJavaDeclaration(
        unit: SourceUnit,
        packageName: String,
        descriptor: DeclarationDescriptor,
        parser: KotlinAstParser,
    ): SourceDefinition? = runCatching {
        val target = JavaBinaryDeclarationTarget.from(descriptor.navigationDeclaration())
            ?: return@runCatching null
        val parsed = parser.parseJava(unit.fileName, unit.text) ?: return@runCatching null
        if (parsed.packageName != packageName) return@runCatching null
        val declaration = when (target.type) {
            Printer.TYPE -> PsiTreeUtil.collectElementsOfType(parsed, PsiClass::class.java)
                .firstOrNull { candidate -> candidate.jvmInternalName() == target.ownerInternalName }
            Printer.METHOD -> PsiTreeUtil.collectElementsOfType(parsed, PsiMethod::class.java)
                .firstOrNull { candidate ->
                    candidate.containingClass?.jvmInternalName() == target.ownerInternalName &&
                        candidate.name == target.name &&
                        candidate.jvmDescriptor() == target.descriptor
                }
            Printer.FIELD -> PsiTreeUtil.collectElementsOfType(parsed, PsiField::class.java)
                .firstOrNull { candidate ->
                    candidate.containingClass?.jvmInternalName() == target.ownerInternalName &&
                        candidate.name == target.name
                }
            else -> null
        } ?: return@runCatching null
        sourceDefinition(unit, declaration.nameIdentifier?.textRange ?: return@runCatching null)
    }.getOrNull()

    private fun sourceDefinition(
        unit: SourceUnit,
        range: org.jetbrains.kotlin.com.intellij.openapi.util.TextRange,
    ): SourceDefinition {
        val external = documents.register(
            origin = unit.origin,
            displayName = unit.displayName,
            languageId = unit.languageId,
            text = unit.text,
        )
        return SourceDefinition(external.uri, external.text, range.startOffset, range.endOffset)
    }

    private fun sourceUnits(sourcePath: List<Path>, packageName: String): Sequence<SourceUnit> = sequence {
        val packagePath = packageName.replace('.', '/')
        sourcePath.forEach { root ->
            when {
                Files.isDirectory(root) -> yieldAll(directoryUnits(root, packagePath))
                Files.isRegularFile(root) && root.fileName.toString().isSourceFile() -> {
                    yield(fileUnit(root))
                }
                Files.isRegularFile(root) -> yieldAll(archiveUnits(root, packagePath))
            }
        }
    }

    private fun directoryUnits(root: Path, packagePath: String): Sequence<SourceUnit> {
        val packageDirectory = root.resolve(packagePath)
        if (!Files.isDirectory(packageDirectory)) return emptySequence()
        val sourceFiles = Files.walk(packageDirectory).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.fileName.toString().isSourceFile() }
                .toList()
        }
        return sourceFiles.asSequence().map { path -> fileUnit(path, root) }
    }

    private fun fileUnit(path: Path, root: Path = path.parent ?: path): SourceUnit =
        SourceUnit(
            origin = path.toAbsolutePath().normalize().toString(),
            displayName = root.relativize(path).toString().replace('\\', '/'),
            fileName = path.fileName.toString(),
            languageId = path.fileName.toString().sourceLanguageId(),
        ) {
            Files.readString(path)
        }

    private fun archiveUnits(archive: Path, packagePath: String): Sequence<SourceUnit> {
        val entryNames = archiveIndex(archive).entriesFor(packagePath)
        return entryNames.asSequence().map { entryName ->
            SourceUnit(
                origin = "${archive.toAbsolutePath().normalize()}!/$entryName",
                displayName = entryName,
                fileName = entryName.substringAfterLast('/'),
                languageId = entryName.sourceLanguageId(),
            ) {
                ZipFile(archive.toFile()).use { zip ->
                    val entry = requireNotNull(zip.getEntry(entryName))
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }
        }
    }

    @Synchronized
    private fun archiveIndex(archive: Path): SourceArchiveIndex {
        val normalized = archive.toAbsolutePath().normalize()
        val key = runCatching {
            ArchiveIndexKey(
                path = normalized,
                size = Files.size(normalized),
                modifiedAt = Files.getLastModifiedTime(normalized).to(TimeUnit.NANOSECONDS),
            )
        }.getOrElse { return SourceArchiveIndex.EMPTY }
        archiveIndexes[key]?.let { return it }
        val entryNames = runCatching {
            ZipFile(normalized.toFile()).use { zip ->
                zip.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.isSourceFile() }
                    .map { entry -> entry.name }
                    .toList()
            }
        }.getOrDefault(emptyList())
        val index = SourceArchiveIndex(entryNames)
        archiveIndexes.keys.removeIf { cached -> cached.path == normalized && cached != key }
        archiveIndexes[key] = index
        if (archiveIndexes.size > MAXIMUM_ARCHIVE_INDEXES) {
            archiveIndexes.remove(archiveIndexes.entries.iterator().next().key)
        }
        return index
    }

    private class SourceUnit(
        val origin: String,
        val displayName: String,
        val fileName: String,
        val languageId: String,
        readText: () -> String,
    ) {
        val text: String by lazy(readText)
    }

    private data class ArchiveIndexKey(
        val path: Path,
        val size: Long,
        val modifiedAt: Long,
    )

    private class SourceArchiveIndex(entryNames: List<String>) {
        private val allEntries = entryNames.toList()
        private val packageEntries = LinkedHashMap<String, List<String>>(16, 0.75f, true)

        @Synchronized
        fun entriesFor(packagePath: String): List<String> {
            if (packagePath.isEmpty()) return allEntries
            packageEntries[packagePath]?.let { return it }
            val entries = allEntries.filter { entryName ->
                entryName.startsWith("$packagePath/") || entryName.contains("/$packagePath/")
            }
            packageEntries[packagePath] = entries
            if (packageEntries.size > MAXIMUM_PACKAGE_INDEXES) {
                packageEntries.remove(packageEntries.entries.iterator().next().key)
            }
            return entries
        }

        companion object {
            const val MAXIMUM_PACKAGE_INDEXES = 64
            val EMPTY = SourceArchiveIndex(emptyList())
        }
    }

    private companion object {
        const val MAXIMUM_ARCHIVE_INDEXES = 64
    }
}

private fun String.isSourceFile(): Boolean = endsWith(".kt") || endsWith(".java")

private fun String.sourceLanguageId(): String = if (endsWith(".java")) "java" else "kotlin"

private fun PsiMethod.jvmDescriptor(): String? {
    val parameters = parameterList.parameters.map { parameter ->
        parameter.type.jvmDescriptor() ?: return null
    }
    val result = returnType?.jvmDescriptor() ?: return null
    return "(${parameters.joinToString("")})$result"
}

private fun PsiType.jvmDescriptor(): String? =
    when (this) {
        is PsiEllipsisType -> "[${componentType.jvmDescriptor() ?: return null}"
        is PsiArrayType -> "[${componentType.jvmDescriptor() ?: return null}"
        is PsiPrimitiveType -> when (canonicalText) {
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "void" -> "V"
            else -> null
        }
        is PsiClassType -> {
            val resolved = resolve() ?: return null
            if (resolved is PsiTypeParameter) {
                resolved.extendsListTypes.firstOrNull()?.jvmDescriptor() ?: "Ljava/lang/Object;"
            } else {
                "L${resolved.jvmInternalName() ?: return null};"
            }
        }
        else -> null
    }

private fun PsiClass.jvmInternalName(): String? {
    val classes = generateSequence(this) { candidate -> candidate.containingClass }
        .toList()
        .asReversed()
    if (classes.any { it.name.isNullOrBlank() }) return null
    val packageName = (containingFile as? PsiJavaFile)?.packageName.orEmpty().replace('.', '/')
    val className = classes.joinToString("$") { requireNotNull(it.name) }
    return if (packageName.isEmpty()) className else "$packageName/$className"
}
