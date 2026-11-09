package org.devvikram.societymanagement.common.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentFormatTest {
    @Test
    fun `PDFs are documents but not photos`() {
        val pdf = "%PDF-1.7\n...".toByteArray()
        assertEquals("application/pdf", DocumentFormat.detect(pdf))
        assertNull(ImageFormat.detect(pdf))
    }

    @Test
    fun `images are documents too, anything else is neither`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("image/jpeg", DocumentFormat.detect(jpeg))
        assertNull(DocumentFormat.detect("hello world".toByteArray()))
        assertNull(DocumentFormat.detect("%PD".toByteArray()))
    }
}
