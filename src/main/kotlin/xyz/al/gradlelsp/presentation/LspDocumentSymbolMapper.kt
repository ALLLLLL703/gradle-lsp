package xyz.al.gradlelsp.presentation

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import xyz.al.gradlelsp.symbols.SourceDocumentSymbol
import xyz.al.gradlelsp.symbols.SourceSymbolKind

internal object LspDocumentSymbolMapper {
    fun hierarchical(
        sourceText: String,
        symbols: List<SourceDocumentSymbol>,
        supportedKinds: Set<SymbolKind>,
    ): List<Either<SymbolInformation, DocumentSymbol>> {
        val lines = Utf16LineMap(sourceText)
        return symbols.map { symbol -> Either.forRight(mapDocumentSymbol(symbol, lines, supportedKinds)) }
    }

    fun flat(
        uri: String,
        sourceText: String,
        symbols: List<SourceDocumentSymbol>,
        supportedKinds: Set<SymbolKind>,
    ): List<Either<SymbolInformation, DocumentSymbol>> {
        val lines = Utf16LineMap(sourceText)
        return buildList {
            symbols.forEach { symbol -> addFlat(uri, symbol, containerName = null, lines, supportedKinds) }
        }
    }

    val legacyKinds: Set<SymbolKind> = SymbolKind.entries
        .filter { kind -> kind.value <= SymbolKind.Array.value }
        .toSet()

    private fun mapDocumentSymbol(
        symbol: SourceDocumentSymbol,
        lines: Utf16LineMap,
        supportedKinds: Set<SymbolKind>,
    ): DocumentSymbol =
        DocumentSymbol(
            symbol.name,
            symbol.kind.toLsp(supportedKinds),
            symbol.range(lines),
            Range(
                lines.positionAt(symbol.selectionStartOffset),
                lines.positionAt(symbol.selectionEndOffset),
            ),
        ).apply {
            if (symbol.children.isNotEmpty()) {
                children = symbol.children.map { child -> mapDocumentSymbol(child, lines, supportedKinds) }
            }
        }

    private fun MutableList<Either<SymbolInformation, DocumentSymbol>>.addFlat(
        uri: String,
        symbol: SourceDocumentSymbol,
        containerName: String?,
        lines: Utf16LineMap,
        supportedKinds: Set<SymbolKind>,
    ) {
        add(
            Either.forLeft(
                SymbolInformation(
                    symbol.name,
                    symbol.kind.toLsp(supportedKinds),
                    Location(uri, symbol.range(lines)),
                    containerName,
                ),
            ),
        )
        symbol.children.forEach { child -> addFlat(uri, child, symbol.name, lines, supportedKinds) }
    }

    private fun SourceDocumentSymbol.range(lines: Utf16LineMap): Range =
        Range(lines.positionAt(startOffset), lines.positionAt(endOffset))

    private fun SourceSymbolKind.toLsp(supportedKinds: Set<SymbolKind>): SymbolKind {
        val preferences = when (this) {
            SourceSymbolKind.CLASS -> listOf(SymbolKind.Class, SymbolKind.Struct)
            SourceSymbolKind.INTERFACE -> listOf(SymbolKind.Interface, SymbolKind.Class)
            SourceSymbolKind.ENUM -> listOf(SymbolKind.Enum, SymbolKind.Class)
            SourceSymbolKind.OBJECT -> listOf(SymbolKind.Object, SymbolKind.Class, SymbolKind.Module)
            SourceSymbolKind.FUNCTION -> listOf(SymbolKind.Function, SymbolKind.Method)
            SourceSymbolKind.METHOD -> listOf(SymbolKind.Method, SymbolKind.Function)
            SourceSymbolKind.CONSTRUCTOR -> listOf(SymbolKind.Constructor, SymbolKind.Method)
            SourceSymbolKind.PROPERTY -> listOf(SymbolKind.Property, SymbolKind.Field)
            SourceSymbolKind.VARIABLE -> listOf(SymbolKind.Variable, SymbolKind.Field)
            SourceSymbolKind.CONSTANT -> listOf(SymbolKind.Constant, SymbolKind.Variable)
            SourceSymbolKind.TYPE_ALIAS -> listOf(SymbolKind.Class, SymbolKind.Struct)
            SourceSymbolKind.ENUM_MEMBER -> listOf(SymbolKind.EnumMember, SymbolKind.Constant, SymbolKind.Field)
        }
        return preferences.firstOrNull(supportedKinds::contains)
            ?: supportedKinds.firstOrNull()
            ?: SymbolKind.Variable
    }
}
