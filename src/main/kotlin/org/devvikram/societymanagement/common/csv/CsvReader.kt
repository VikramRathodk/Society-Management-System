package org.devvikram.societymanagement.common.csv

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

// Reading uploaded CSV files (bank statements, resident sheets). Excel users save as CSV first.
object CsvReader {
    val DELIMITERS = setOf(',', ';', '\t', '|')

    // UTF-8 (with or without a byte-order mark); anything that isn't valid UTF-8 is read as Windows-1252,
    // which is what older bank exports use.
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            String(body, Charset.forName("windows-1252"))
        }
    }

    // RFC 4180 records: quoted cells may hold the separator, quotes ("") and line breaks.
    fun records(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
            } else when (c) {
                '"' -> if (cell.isBlank()) { cell.setLength(0); quoted = true } else cell.append(c)
                delimiter -> { row.add(cell.toString()); cell.setLength(0) }
                '\r', '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row); row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString()); rows.add(row)
        }
        return rows
    }

    // The separator that splits the first lines most consistently into several cells.
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(30).toList()
        return DELIMITERS.maxByOrNull { d -> sample.count { line -> line.count { it == d } >= 2 } * 100 + sample.sumOf { line -> line.count { it == d } }.coerceAtMost(99) }
            ?: ','
    }
}
