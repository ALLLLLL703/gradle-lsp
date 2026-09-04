package xyz.al.gradlelsp.presentation

import org.eclipse.lsp4j.Position

internal class Utf16LineMap(private val text: String) {
    private val lineStarts: IntArray = buildLineStarts(text)

    fun positionAt(offset: Int): Position {
        val safeOffset = offset.coerceIn(0, text.length)
        val searchResult = lineStarts.binarySearch(safeOffset)
        val line = if (searchResult >= 0) searchResult else -searchResult - 2
        return Position(line, safeOffset - lineStarts[line])
    }

    fun offsetAt(position: Position): Int? {
        if (position.line !in lineStarts.indices || position.character < 0) return null

        val lineStart = lineStarts[position.line]
        val lineEnd = contentEnd(position.line)
        if (position.character > lineEnd - lineStart) return null
        return lineStart + position.character
    }

    private fun contentEnd(line: Int): Int {
        if (line == lineStarts.lastIndex) return text.length

        var end = lineStarts[line + 1]
        if (end > lineStarts[line] && text[end - 1] == '\n') end -= 1
        if (end > lineStarts[line] && text[end - 1] == '\r') end -= 1
        return end
    }

    private fun buildLineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        var offset = 0
        while (offset < text.length) {
            when (text[offset]) {
                '\r' -> {
                    offset += if (offset + 1 < text.length && text[offset + 1] == '\n') 2 else 1
                    starts += offset
                }

                '\n' -> {
                    offset += 1
                    starts += offset
                }

                else -> offset += 1
            }
        }
        return starts.toIntArray()
    }
}
