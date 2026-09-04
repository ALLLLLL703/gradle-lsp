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
