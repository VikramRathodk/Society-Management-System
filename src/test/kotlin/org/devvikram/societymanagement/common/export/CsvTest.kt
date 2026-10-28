package org.devvikram.societymanagement.common.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CsvTest {

    @Test
    fun `writes a header and rows with CRLF line endings, nulls as empty cells`() {
        assertEquals("id,name\r\n1,Asha\r\n2,\r\n", Csv.write(listOf("id", "name"), listOf(listOf(1, "Asha"), listOf(2, null))))
    }

    @Test
    fun `quotes cells containing commas, quotes or line breaks`() {
        assertEquals(
            "a\r\n\"x, y\"\r\n\"say \"\"hi\"\"\"\r\n\"two\nlines\"\r\n",
            Csv.write(listOf("a"), listOf(listOf("x, y"), listOf("say \"hi\""), listOf("two\nlines"))),
        )
    }

    @Test
    fun `neutralises cells a spreadsheet would treat as formulas`() {
        assertEquals("a\r\n'=SUM(A1)\r\n'+91 98\r\n'@cmd\r\n", Csv.write(listOf("a"), listOf(listOf("=SUM(A1)"), listOf("+91 98"), listOf("@cmd"))))
    }
}
