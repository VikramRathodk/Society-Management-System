package org.devvikram.societymanagement.common.export

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

// Minimal RFC 4180 CSV for report downloads. Cells that a spreadsheet would run as a formula
// (=, +, -, @, tab, CR) are prefixed with a quote, since exported text comes from users. Only strings
// are neutralised, so a negative number stays a number.
object Csv {
    fun write(header: List<String>, rows: List<List<Any?>>): String = buildString {
        (listOf(header) + rows).forEach { row ->
            append(row.joinToString(",") { cell(it) })
            append("\r\n")
        }
    }

    fun download(fileName: String, content: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType("text", "csv", Charsets.UTF_8))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString(),
            )
            .body(content.toByteArray(Charsets.UTF_8))

    private fun cell(value: Any?): String {
        val text = value?.toString() ?: return ""
        val safe = if (value is String && text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
